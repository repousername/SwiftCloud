package swift.client;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import swift.clocks.CausalityClock;
import swift.clocks.ClockFactory;
import swift.clocks.IncrementalTripleTimestampGenerator;
import swift.clocks.Timestamp;
import swift.clocks.TimestampMapping;
import swift.clocks.TripleTimestamp;
import swift.crdt.CRDTIdentifier;
import swift.crdt.interfaces.CRDT;
import swift.crdt.interfaces.CRDTUpdate;
import swift.crdt.interfaces.CachePolicy;
import swift.crdt.interfaces.ObjectUpdatesListener;
import swift.crdt.interfaces.TxnHandle;
import swift.crdt.interfaces.TxnLocalCRDT;
import swift.crdt.interfaces.TxnStatus;
import swift.crdt.operations.CRDTObjectUpdatesGroup;
import swift.exceptions.NetworkException;
import swift.exceptions.NoSuchObjectException;
import swift.exceptions.VersionNotFoundException;
import swift.exceptions.WrongTypeException;

/**
 * Implementation of abstract SwiftCloud transaction with unspecified isolation
 * level. It keeps track of its state and mediates between the application and
 * {@link TxnManager} communicating with the store. Implementations define the
 * read algorithm for CRDT and transaction dependencies, and should ensure
 * session guarantees between transactions.
 * <p>
 * Transaction is <em>read-only transaction</em> if it does not issue any update
 * operation; transaction is <em>update transaction</em> otherwise. Update
 * transaction uses timestamp for its updates.
 * <p>
 * A transaction is first locally committed with a client timestamp, and then
 * globally committed with a stable system timestamp assigned by server to
 * facilitate efficient timestamps summary. The mapping between these timestamps
 * is defined within a TimestamMapping object of the transaction.
 * <p>
 * This base implementation primarily keeps track of transaction states and
 * updates on objects.
 * <p>
 * Instances shall be generated using and managed by {@link TxnManager}
 * implementation. Thread-safe.
 * 
 * @author mzawirski
 */
abstract class AbstractTxnHandle implements TxnHandle, Comparable<AbstractTxnHandle> {

    protected final TxnManager manager;
    protected final boolean readOnly;
    protected CachePolicy cachePolicy;
    protected final TimestampMapping timestampMapping;
    protected final CausalityClock updatesDependencyClock;
    protected final IncrementalTripleTimestampGenerator timestampSource;
    protected final Map<CRDTIdentifier, CRDTObjectUpdatesGroup<?>> localObjectOperations;
    protected TxnStatus status;
    protected CommitListener commitListener;
    protected final Map<TxnLocalCRDT<?>, ObjectUpdatesListener> objectUpdatesListeners;

    /**
     * Creates an update transaction.
     * 
     * @param manager
     *            manager maintaining this transaction
     * @param cachePolicy
     *            cache policy used by this transaction
     * @param timestampMapping
     *            timestamp and timestamp mapping information used for all
     *            updates of this transaction
     */
    AbstractTxnHandle(final TxnManager manager, final CachePolicy cachePolicy, final TimestampMapping timestampMapping) {
        this.manager = manager;
        this.readOnly = false;
        this.cachePolicy = cachePolicy;
        this.timestampMapping = timestampMapping;
        this.updatesDependencyClock = ClockFactory.newClock();
        this.timestampSource = new IncrementalTripleTimestampGenerator(timestampMapping);
        this.localObjectOperations = new HashMap<CRDTIdentifier, CRDTObjectUpdatesGroup<?>>();
        this.status = TxnStatus.PENDING;
        this.objectUpdatesListeners = new HashMap<TxnLocalCRDT<?>, ObjectUpdatesListener>();
    }

    /**
     * Creates a read-only transaction.
     * 
     * @param manager
     *            manager maintaining this transaction
     * @param cachePolicy
     *            cache policy used by this transaction
     * @param timestampMapping
     *            timestamp and timestamp mapping information used for all
     *            updates of this transaction
     */
    AbstractTxnHandle(final TxnManager manager, final CachePolicy cachePolicy) {
        this.manager = manager;
        this.readOnly = true;
        this.cachePolicy = cachePolicy;
        this.timestampMapping = null;
        this.updatesDependencyClock = ClockFactory.newClock();
        this.timestampSource = null;
        this.localObjectOperations = new HashMap<CRDTIdentifier, CRDTObjectUpdatesGroup<?>>();
        this.status = TxnStatus.PENDING;
        this.objectUpdatesListeners = new HashMap<TxnLocalCRDT<?>, ObjectUpdatesListener>();
    }

    @Override
    public synchronized <V extends CRDT<V>, T extends TxnLocalCRDT<V>> T get(CRDTIdentifier id, boolean create,
            Class<V> classOfV) throws WrongTypeException, NoSuchObjectException, VersionNotFoundException,
            NetworkException {
        return get(id, create, classOfV, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <V extends CRDT<V>, T extends TxnLocalCRDT<V>> T get(CRDTIdentifier id, boolean create,
            Class<V> classOfV, ObjectUpdatesListener listener) throws WrongTypeException, NoSuchObjectException,
            VersionNotFoundException, NetworkException {
        assertStatus(TxnStatus.PENDING);
        try {
            if (SwiftImpl.DEFAULT_LISTENER_FOR_GET && listener == null)
                listener = SwiftImpl.DEFAULT_LISTENER;
            return getImpl(id, create, classOfV, listener);
        } catch (ClassCastException x) {
            throw new WrongTypeException(x.getMessage());
        }
    }

    @Override
    public void commit() {
        final Semaphore commitSem = new Semaphore(0);
        commitAsync(new CommitListener() {
            @Override
            public void onGlobalCommit(TxnHandle transaction) {
                commitSem.release();
            }
        });
        commitSem.acquireUninterruptibly();
    }

    @Override
    public synchronized void commitAsync(final CommitListener listener) {
        assertStatus(TxnStatus.PENDING);
        this.commitListener = listener;
        manager.commitTxn(this);
    }

    @Override
    public synchronized void rollback() {
        assertStatus(TxnStatus.PENDING);
        manager.discardTxn(this);
        status = TxnStatus.CANCELLED;
    }

    public synchronized TxnStatus getStatus() {
        return status;
    }

    @Override
    public synchronized TripleTimestamp nextTimestamp() {
        assertStatus(TxnStatus.PENDING);
        assertNotReadOnly();
        return timestampSource.generateNew();
    }

    @Override
    public synchronized <V extends CRDT<V>> void registerOperation(CRDTIdentifier id, CRDTUpdate<V> op) {
        assertStatus(TxnStatus.PENDING);
        assertNotReadOnly();

        @SuppressWarnings("unchecked")
        CRDTObjectUpdatesGroup<V> operationsGroup = (CRDTObjectUpdatesGroup<V>) localObjectOperations.get(id);
        if (operationsGroup == null) {
            operationsGroup = new CRDTObjectUpdatesGroup<V>(id, getTimestampMapping(), null,
                    getUpdatesDependencyClock());
            localObjectOperations.put(id, operationsGroup);
        }
        operationsGroup.append(op);
    }

    @Override
    public synchronized <V extends CRDT<V>> void registerObjectCreation(CRDTIdentifier id, V creationState) {
        if (isReadOnly()) {
            return;
        }

        final CRDTObjectUpdatesGroup<V> operationsGroup = new CRDTObjectUpdatesGroup<V>(id, timestampMapping,
                creationState, getUpdatesDependencyClock());
        if (localObjectOperations.put(id, operationsGroup) != null) {
            throw new IllegalStateException("Object creation operation was preceded by some another operation");
        }
    }

    /**
     * @return timestamp mapping used by all updates of this transaction; note
     *         that it may be mutated
     */
    TimestampMapping getTimestampMapping() {
        return timestampMapping;
    }

    /**
     * Marks transaction as locally committed.
     */
    synchronized void markLocallyCommitted() {
        assertStatus(TxnStatus.PENDING);
        status = TxnStatus.COMMITTED_LOCAL;
    }

    /**
     * Adds a (new) system timestamp to this transaction and marks transaction
     * as globally committed.
     * 
     * @param globalTimestamp
     *            a system timestamp for this transaction; ignored for read-only
     *            transaction
     */
    void markGloballyCommitted(final Timestamp systemTimestamp) {
        if (!isReadOnly() && !getAllUpdates().isEmpty() && systemTimestamp == null) {
            throw new IllegalStateException("no system timestamp for update transaction");
        }

        boolean justGloballyCommitted = false;
        synchronized (this) {
            assertStatus(TxnStatus.COMMITTED_LOCAL, TxnStatus.COMMITTED_GLOBAL);
            if (status == TxnStatus.COMMITTED_LOCAL) {
                justGloballyCommitted = true;
                status = TxnStatus.COMMITTED_GLOBAL;
            }
            if (systemTimestamp != null) {
                timestampMapping.addSystemTimestamp(systemTimestamp);
            }
        }
        if (justGloballyCommitted) {
            if (commitListener != null) {
                commitListener.onGlobalCommit(this);
            }
        }
    }

    /**
     * @return true when the transaction is read-only
     */
    boolean isReadOnly() {
        return readOnly;
    }

    /**
     * @return a collection of update operations group on objects updated by
     *         this transactions; empty for read-only transaction
     */
    synchronized Collection<CRDTObjectUpdatesGroup<?>> getAllUpdates() {
        assertStatus(TxnStatus.CANCELLED, TxnStatus.COMMITTED_LOCAL, TxnStatus.COMMITTED_GLOBAL);
        return localObjectOperations.values();
    }

    /**
     * @return an update operations group on object updated by this transaction;
     *         null if object is not updated by this transaction
     */
    synchronized CRDTObjectUpdatesGroup<?> getObjectUpdates(CRDTIdentifier id) {
        assertStatus(TxnStatus.COMMITTED_LOCAL, TxnStatus.COMMITTED_GLOBAL);
        return localObjectOperations.get(id);
    }

    synchronized CausalityClock getUpdatesDependencyClock() {
        return updatesDependencyClock;
    }

    /**
     * Implementation of read request, can use {@link #manager} for that
     * purposes. Implementation is responsible for maintaining dependency clock
     * of the transaction using
     * {@link #updateUpdatesDependencyClock(CausalityClock)} to ensure that it
     * depends on every version read by the transaction.
     */
    protected abstract <V extends CRDT<V>, T extends TxnLocalCRDT<V>> T getImpl(CRDTIdentifier id, boolean create,
            Class<V> classOfV, ObjectUpdatesListener updatesListener) throws WrongTypeException, NoSuchObjectException,
            VersionNotFoundException, NetworkException;

    /**
     * Updates dependency clock of the transaction.
     * 
     * @param clock
     *            clock to include in the dependency clock of this transaction
     * @throws IllegalStateException
     *             when transaction is not pending or locally committed
     */
    protected void updateUpdatesDependencyClock(final CausalityClock clock) {
        assertStatus(TxnStatus.PENDING, TxnStatus.COMMITTED_LOCAL);
        updatesDependencyClock.merge(clock);
    }

    protected synchronized void assertStatus(final TxnStatus... expectedStatuses) {
        for (final TxnStatus expectedStatus : expectedStatuses) {
            if (status == expectedStatus) {
                return;
            }
        }
        throw new IllegalStateException("Unexpected transaction status: was " + status + ", expected "
                + Arrays.asList(expectedStatuses));
    }

    protected void assertNotReadOnly() {
        if (readOnly) {
            throw new IllegalStateException("update request for read-only transaction");
        }
    }

    @Override
    public String toString() {
        return (readOnly ? "read-only" : "update") + " transaction ts=" + timestampMapping;
    }

    @Override
    public int compareTo(AbstractTxnHandle o) {
        return Long.signum(orderingScore() - o.orderingScore());
    }

    private long orderingScore() {
        return getTimestampMapping() == null ? 0 : getTimestampMapping().getClientTimestamp().getCounter();
    }
}
