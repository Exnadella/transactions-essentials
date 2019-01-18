package com.atomikos.recovery.xa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;

import com.atomikos.datasource.xa.RecoveryScan;
import com.atomikos.datasource.xa.RecoveryScan.XidSelector;
import com.atomikos.datasource.xa.XID;
import com.atomikos.icatch.config.Configuration;
import com.atomikos.icatch.event.transaction.TransactionAbortedEvent;
import com.atomikos.icatch.event.transaction.TransactionHeuristicEvent;
import com.atomikos.logging.Logger;
import com.atomikos.logging.LoggerFactory;
import com.atomikos.publish.EventPublisher;
import com.atomikos.recovery.LogException;
import com.atomikos.recovery.LogReadException;
import com.atomikos.recovery.PendingTransactionRecord;
import com.atomikos.recovery.RecoveryLog;
import com.atomikos.recovery.TxState;

public class XARecoveryManager {
private static final Logger LOGGER = LoggerFactory.createLogger(XARecoveryManager.class);
	
	private static XARecoveryManager instance;
	
	private RecoveryLog log;
	private XidSelector xidSelector;

	private Map<String,PreviousXidRepository> previousXidRepositoryMap = new HashMap<String, PreviousXidRepository>();
	
	
	public XARecoveryManager(RecoveryLog recoveryLog, final String tmUniqueName) {
		this.log=recoveryLog;
		this.xidSelector=new XidSelector() {
			@Override
			public boolean selects(XID xid) {
				boolean ret = false;
				String branch = xid.getBranchQualifierAsString();
				
                if ( branch.startsWith ( tmUniqueName ) ) {
                	ret = true;
                    if(LOGGER.isDebugEnabled()){
                    	LOGGER.logDebug("Resource " + tmUniqueName + " recovering XID: " + xid);
                    }
                } else {
                	if(LOGGER.isDebugEnabled()){
                		LOGGER.logDebug("Resource " + tmUniqueName + ": XID " + xid + 
                		" with branch " + branch + " is not under my responsibility");
                	}
                }
                return ret;
			}

									
		}; 
	}


	public static XARecoveryManager getInstance() {
		return instance;
	}


	public static void installXARecoveryManager(RecoveryLog recoveryLog, String tmUniqueName) {
		if (recoveryLog == null) {
			instance = null;
		} else {
			instance = new XARecoveryManager(recoveryLog, tmUniqueName);
			
		}
	}


	public boolean recover(XAResource xaResource, long startOfRecoveryScan, String uniqueResourceName) throws XAException, LogReadException {
		Collection<PendingTransactionRecord> expiredCommittingCoordinators = log.getExpiredPendingTransactionRecordsAt(startOfRecoveryScan);
		List<XID> xidsToRecover = retrievePreparedXidsFromXaResource(xaResource);
		long xidDetectionTime = System.currentTimeMillis();
		PreviousXidRepository previousXidRepository = getPreviousXidRepository(uniqueResourceName);
		List<XID> expiredPreviousXids = previousXidRepository.findXidsExpiredAt(startOfRecoveryScan);
		List<XID> xidsToStoreForNextScan = new ArrayList<>();
		boolean success = recoverXids(xidsToRecover, expiredPreviousXids, expiredCommittingCoordinators, xaResource, xidsToStoreForNextScan);
		previousXidRepository.remember(xidsToStoreForNextScan, xidDetectionTime+Configuration.getConfigProperties().getMaxTimeout());
		if (success) {
			previousXidRepository.forgetXidsExpiredAt(startOfRecoveryScan);	
		}		
		return success;
	}




	private PreviousXidRepository getPreviousXidRepository(
			String uniqueResourceName) {
		PreviousXidRepository ret = previousXidRepositoryMap.get(uniqueResourceName);
		if (ret == null) {
			ret = new InMemoryPreviousXidRepository();
			previousXidRepositoryMap.put(uniqueResourceName, ret);
		}
		return ret;
	}


	private boolean recoverXids(List<XID> xidsToRecover, List<XID> expiredPreviousXids,
			Collection<PendingTransactionRecord> expiredCommittingCoordinators, XAResource xaResource,
			List<XID> xidsToStoreForNextScan) {
		boolean success = true;
		List<String> expiredCommittingCoordinatorIds = new ArrayList<String>();
		for (PendingTransactionRecord entry : expiredCommittingCoordinators) {
			expiredCommittingCoordinatorIds.add(entry.id);
		}
		for (XID xid : xidsToRecover) {
			String coordinatorId = xid.getGlobalTransactionIdAsString();
			if (expiredCommittingCoordinatorIds.contains(coordinatorId)) {
				success = success && replayCommit(xid, xaResource);
			} else if (expiredPreviousXids.contains(xid)) {
				success = success && presumedAbort(xid, xaResource);
			} else {
				xidsToStoreForNextScan.add(xid);
			}
		}
		return success;
	}
	
	private boolean replayCommit(XID xid, XAResource xaResource) {
		if (LOGGER.isDebugEnabled()) LOGGER.logDebug("Replaying commit of xid: " + xid);
		boolean forgetInLog = false;
		try {
			xaResource.commit(xid, false);
			forgetInLog = true;
		} catch (XAException e) {
			if (alreadyHeuristicallyTerminatedByResource(e)) {
				forgetInLog = handleHeuristicTerminationByResource(xid, xaResource, e, true);;
			} else if (xidTerminatedInResourceByConcurrentCommit(e)) {
				forgetInLog = true;
			} else {
				LOGGER.logWarning("Transient error while replaying commit - will retry later...", e);
			}
		}
		
		return forgetInLog;
	}
	
	private boolean alreadyHeuristicallyTerminatedByResource(XAException e) {
		boolean ret = false;
		switch (e.errorCode) {
		case XAException.XA_HEURHAZ:
		case XAException.XA_HEURCOM:
		case XAException.XA_HEURMIX:
		case XAException.XA_HEURRB:
			ret = true;
		}
		return ret;
	}
	
	private boolean xidTerminatedInResourceByConcurrentCommit(XAException e) {
		return xidNoLongerKnownByResource(e);
	}
	
	private boolean xidTerminatedInResourceByConcurrentRollback(XAException e) {
		return xidNoLongerKnownByResource(e);
	}

	private boolean xidNoLongerKnownByResource(XAException e) {
		boolean ret = false;
		switch (e.errorCode) {
		case XAException.XAER_NOTA:
		case XAException.XAER_INVAL:
			ret = true;
		}
		return ret;
	}
	
	private boolean handleHeuristicTerminationByResource(XID xid,
			XAResource xaResource, XAException e, boolean commitDesired) {
		boolean forgetInLog = true;
		try {
			notifyLogOfHeuristic(xid, e, commitDesired);
			if (e.errorCode != XAException.XA_HEURHAZ) {
				forgetXidInXaResource(xid, xaResource);	
			} else {
				forgetInLog = false;
			}
		} catch (LogException transientLogWriteException) {
			LOGGER.logWarning("Failed to log heuristic termination of Xid: "+xid+" - ignoring to retry later", transientLogWriteException);
		}
		return forgetInLog;
	}
	
	private void forgetXidInXaResource(XID xid, XAResource xaResource) {
		try {
			xaResource.forget(xid);
		} catch (XAException e) {
			LOGGER.logWarning("Unexpected error during forget - ignoring", e);
			// ignore: worst case, heuristic xid is presented again on next
			// recovery scan
		}
	}

	private void notifyLogOfHeuristic(XID xid, XAException e, boolean commitDesired ) throws LogException {
		switch (e.errorCode) {
		case XAException.XA_HEURHAZ:
			fireTransactionHeuristicEvent(xid, TxState.HEUR_HAZARD);
			break;
		case XAException.XA_HEURCOM:
			if(!commitDesired){
				fireTransactionHeuristicEvent(xid, TxState.HEUR_COMMITTED);	
			}
			break;
		case XAException.XA_HEURMIX:
			fireTransactionHeuristicEvent(xid, TxState.HEUR_MIXED);
			break;
		case XAException.XA_HEURRB:
			if(commitDesired) {
				fireTransactionHeuristicEvent(xid, TxState.HEUR_ABORTED);	
			}
			break;
		default:
			break;
		}
	}

	
	private void fireTransactionHeuristicEvent(XID xid, TxState state) {
		TransactionHeuristicEvent event = new TransactionHeuristicEvent(xid.getGlobalTransactionIdAsString(), state.toString() + " for XID " + xid.toString());
		EventPublisher.publish(event);
	}


	private boolean presumedAbort(XID xid, XAResource xaResource) {
		boolean ret = false;
		if (LOGGER.isDebugEnabled()) LOGGER.logDebug("Presumed abort of xid: " + xid);
		try {
			xaResource.rollback(xid);
			EventPublisher.publish(new TransactionAbortedEvent(xid.getGlobalTransactionIdAsString()));
			ret = true;
		} catch (XAException e) {
			if (alreadyHeuristicallyTerminatedByResource(e)) {
				ret = handleHeuristicTerminationByResource(xid, xaResource, e, false);
			} else if (xidTerminatedInResourceByConcurrentRollback(e)) {
				ret = true;
			} else {
				LOGGER.logWarning("Unexpected exception during recovery - ignoring to retry later...", e);
			}
		}
		
		return ret;
	}


	private List<XID> retrievePreparedXidsFromXaResource(XAResource xaResource) throws XAException {
		List<XID> ret = new ArrayList<XID>();
		try {
			ret = RecoveryScan.recoverXids(xaResource, xidSelector);
		} catch (XAException e) {
			LOGGER.logWarning("Error while retrieving xids from resource - will retry later...", e);
			throw e;
		}
		return ret;
	}
}
