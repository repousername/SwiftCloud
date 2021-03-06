package swift.application.ycsb;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;

import swift.client.SwiftImpl;
import swift.client.SwiftOptions;
import swift.crdt.core.CachePolicy;
import swift.crdt.core.IsolationLevel;
import swift.crdt.core.ObjectUpdatesListener;
import swift.crdt.core.SwiftSession;
import swift.crdt.core.TxnHandle;
import swift.dc.DCConstants;
import swift.exceptions.NetworkException;
import swift.exceptions.NoSuchObjectException;
import swift.exceptions.SwiftException;
import swift.exceptions.VersionNotFoundException;
import swift.exceptions.WrongTypeException;
import swift.utils.SafeLog;
import swift.utils.SafeLog.ReportType;
import sys.Sys;

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;

/**
 * Abstract YCSB client for SwiftCloud that handles connection, configuration
 * and errors, but does not define the actual database operations.
 * 
 * @author mzawirski
 */
public abstract class AbstractSwiftClient extends DB {
    public static final int ERROR_NETWORK = -1;
    public static final int ERROR_NOT_FOUND = -2;
    public static final int ERROR_WRONG_TYPE = -3;
    public static final int ERROR_PRUNING_RACE = -4;
    public static final int ERROR_UNSUPPORTED = -5;
    public static final IsolationLevel DEFAULT_ISOLATION_LEVEL = IsolationLevel.SNAPSHOT_ISOLATION;
    public static final CachePolicy DEFAULT_CACHE_POLICY = CachePolicy.CACHED;
    public static final ObjectUpdatesListener DEFAULT_NOTIFICATIONS_SUBSCRIBER = TxnHandle.UPDATES_SUBSCRIBER;
    public static final boolean DEFAULT_ASYNC_COMMIT = false;
    public static final boolean REPORT_EVERY_OPERATION = false;

    protected SwiftSession session;
    protected IsolationLevel isolationLevel = DEFAULT_ISOLATION_LEVEL;
    protected CachePolicy cachePolicy = DEFAULT_CACHE_POLICY;
    protected ObjectUpdatesListener notificationsSubscriber = DEFAULT_NOTIFICATIONS_SUBSCRIBER;
    protected boolean asyncCommit = DEFAULT_ASYNC_COMMIT;
    private UUID sessionId;
    private boolean reportEveryOperation = REPORT_EVERY_OPERATION;

    @Override
    public void init() throws DBException {
        super.init();
        Sys.init();

        final Properties props = getProperties();
        String hostname = props.getProperty("swift.hostname");
        if (hostname == null) {
            hostname = "localhost";
        }
        String portString = props.getProperty("swift.port");
        final int port;
        if (portString != null) {
            port = Integer.parseInt(portString);
        } else {
            port = DCConstants.SURROGATE_PORT;
        }

        // TODO: document properties
        if (props.getProperty("swift.isolationLevel") != null) {
            try {
                isolationLevel = IsolationLevel.valueOf(props.getProperty("swift.isolationLevel"));
            } catch (IllegalArgumentException x) {
                System.err.println("Could not recognized isolationLevel=" + props.getProperty("swift.isolationLevel"));
            }
        }
        if (props.getProperty("swift.cachePolicy") != null) {
            try {
                cachePolicy = CachePolicy.valueOf(props.getProperty("swift.cachePolicy"));
            } catch (IllegalArgumentException x) {
                System.err.println("Could not recognized cachePolicy=" + props.getProperty("swift.cachePolicy"));
            }
        }
        if (props.getProperty("swift.notifications") != null) {
            notificationsSubscriber = Boolean.parseBoolean(props.getProperty("swift.notifications")) ? TxnHandle.UPDATES_SUBSCRIBER
                    : null;
        }
        if (props.getProperty("swift.asyncCommit") != null) {
            asyncCommit = Boolean.parseBoolean(props.getProperty("swift.asyncCommit"));
        }
        if (props.getProperty("swift.reportEveryOperation") != null) {
            reportEveryOperation = Boolean.parseBoolean(props.getProperty("swift.reportEveryOperation"));
        }

        final SwiftOptions options = new SwiftOptions(hostname, port, props);
        sessionId = UUID.randomUUID();
        session = SwiftImpl.newSingleSessionInstance(options, sessionId.toString());
    }

    @Override
    public void cleanup() throws DBException {
        super.cleanup();
        if (session == null) {
            return;
        }
        session.stopScout(true);
        session = null;
    }

    @Override
    public int read(String table, String key, Set<String> fields, HashMap<String, ByteIterator> result) {
        long startTimestamp = 0;
        if (reportEveryOperation) {
            startTimestamp = System.currentTimeMillis();
        }
        final int returnCode = readTxn(table, key, fields, result);
        if (reportEveryOperation) {
            if (returnCode == 0) {
                final long durationMs = System.currentTimeMillis() - startTimestamp;
                SafeLog.report(ReportType.APP_OP, sessionId, "read", durationMs);
                reportStalenessOnRead(table, key, result, startTimestamp);
            } else {
                SafeLog.report(ReportType.APP_OP_FAILURE, sessionId, "read", errorCause(returnCode));
            }
        }
        return returnCode;
    }

    protected int readTxn(String table, String key, Set<String> fields, HashMap<String, ByteIterator> result) {
        TxnHandle txn = null;
        try {
            txn = session.beginTxn(isolationLevel, cachePolicy, true);
            int res = readImpl(txn, table, key, fields, result);
            if (res == 0) {
                txnCommit(txn);
            }
            return res;
        } catch (SwiftException x) {
            return handleException(x);
        } finally {
            cleanUpTxn(txn);
        }
    }

    @Override
    public int scan(String table, String startkey, int recordcount, Set<String> fields,
            Vector<HashMap<String, ByteIterator>> result) {
        return ERROR_UNSUPPORTED;
    }

    @Override
    public int update(String table, String key, HashMap<String, ByteIterator> values) {
        long startTimestamp = 0;
        if (reportEveryOperation) {
            startTimestamp = System.currentTimeMillis();
            setTimestamp( startTimestamp, values);
            reportStalenessOnWrite(table, key, values);
        }
        final int returnCode = updateTxn(table, key, values);
        if (reportEveryOperation) {
            if (returnCode == 0) {
                final long durationMs = System.currentTimeMillis() - startTimestamp;
                SafeLog.report(ReportType.APP_OP, sessionId, "update", durationMs);
            } else {
                SafeLog.report(ReportType.APP_OP_FAILURE, sessionId, "update", errorCause(returnCode));
            }
        }
        return returnCode;
    }

    private int updateTxn(String table, String key, HashMap<String, ByteIterator> values) {
        TxnHandle txn = null;
        try {
            txn = session.beginTxn(isolationLevel, cachePolicy, false);
            int res = updateImpl(txn, table, key, values);
            if (res == 0) {
                txnCommit(txn);
            }
            return res;
        } catch (SwiftException x) {
            return handleException(x);
        } finally {
            cleanUpTxn(txn);
        }
    }
    
    private void setTimestamp( long ts, HashMap<String, ByteIterator> values) {
        for( ByteIterator bit : values.values())
            bit.setTimestamp(ts);
    }

    @Override
    public int insert(String table, String key, HashMap<String, ByteIterator> values) {
        long startTimestamp = 0;
        if (reportEveryOperation) {
            startTimestamp = System.currentTimeMillis();
            setTimestamp( startTimestamp, values);
            reportStalenessOnWrite(table, key, values);
        }
        final int returnCode = insertTxn(table, key, values);
        if (reportEveryOperation) {
            if (returnCode == 0) {
                final long durationMs = System.currentTimeMillis() - startTimestamp;
                SafeLog.report(ReportType.APP_OP, sessionId, "insert", durationMs);
            } else {
                SafeLog.report(ReportType.APP_OP_FAILURE, sessionId, "insert", errorCause(returnCode));
            }
        }
        return returnCode;
    }

    private int insertTxn(String table, String key, HashMap<String, ByteIterator> values) {
        TxnHandle txn = null;
        try {
            // WISHME: blind updates would help here
            txn = session.beginTxn(isolationLevel, cachePolicy, false);
            int res = insertImpl(txn, table, key, values);
            if (res == 0) {
                txnCommit(txn);
            }
            return res;
        } catch (SwiftException x) {
            return handleException(x);
        } finally {
            cleanUpTxn(txn);
        }
    }

    @Override
    public int delete(String table, String key) {
        return ERROR_UNSUPPORTED;
    }

    protected abstract int readImpl(TxnHandle txn, String table, String key, Set<String> fields,
            HashMap<String, ByteIterator> result) throws WrongTypeException, NoSuchObjectException,
            VersionNotFoundException, NetworkException;

    protected abstract int updateImpl(TxnHandle txn, String table, String key, HashMap<String, ByteIterator> values)
            throws WrongTypeException, NoSuchObjectException, VersionNotFoundException, NetworkException;

    protected abstract int insertImpl(TxnHandle txn, String table, String key, HashMap<String, ByteIterator> values)
            throws WrongTypeException, NoSuchObjectException, VersionNotFoundException, NetworkException;

    private void cleanUpTxn(TxnHandle txn) {
        if (txn != null && !txn.getStatus().isTerminated()) {
            txn.rollback();
        }
    }

    private void txnCommit(TxnHandle txn) {
        if (asyncCommit) {
            txn.commitAsync(null);
        } else {
            txn.commit();
        }
    }

    private int handleException(final SwiftException x) {
        x.printStackTrace();
        if (x instanceof WrongTypeException) {
            return ERROR_WRONG_TYPE;
        } else if (x instanceof NoSuchObjectException) {
            return ERROR_NOT_FOUND;
        } else if (x instanceof VersionNotFoundException) {
            return ERROR_PRUNING_RACE;
        } else if (x instanceof NetworkException) {
            return ERROR_NETWORK;
        } else {
            System.err.println("Unexepcted type of exception");
            return -666;
        }
    }

    private void reportStalenessOnRead(String table, String key, HashMap<String, ByteIterator> result,
            long readTimestamp) {
        if (!ReportType.STALENESS_YCSB_READ.isEnabled()) {
            return;
        }
        String prefixS = table + ":" + key + ":";
        for (Entry<String, ByteIterator> entry : result.entrySet()) {
            ByteIterator it = entry.getValue();
            SafeLog.report(ReportType.STALENESS_YCSB_READ, sessionId, prefixS + entry.getKey(), it.getTimestamp(),
                    readTimestamp);
        }
    }

    private void reportStalenessOnWrite(String table, String key, HashMap<String, ByteIterator> values) {
        if (!ReportType.STALENESS_YCSB_WRITE.isEnabled()) {
            return;
        }
        String prefixS = table + ":" + key + ":";
        for (Entry<String, ByteIterator> entry : values.entrySet()) {
            ByteIterator it = entry.getValue();
            SafeLog.report(ReportType.STALENESS_YCSB_WRITE, sessionId, prefixS + entry.getKey(), it.getTimestamp());
        }
    }

    private String errorCause(int errorCode) {
        switch (errorCode) {
        case ERROR_NETWORK:
            return "network_failure";
        case ERROR_NOT_FOUND:
            return "object_not_found";
        case ERROR_PRUNING_RACE:
            return "version_pruned";
        case ERROR_UNSUPPORTED:
            return "unsupported_operation";
        case ERROR_WRONG_TYPE:
            return "wrong_object_type";
        default:
            return "unknown";
        }
    }
}
