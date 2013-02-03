package edu.washington.escience.myriad.parallel;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.netty.channel.ChannelFuture;
import org.slf4j.LoggerFactory;

import edu.washington.escience.myriad.DbException;
import edu.washington.escience.myriad.MyriaConstants;
import edu.washington.escience.myriad.Schema;
import edu.washington.escience.myriad.TupleBatch;
import edu.washington.escience.myriad.TupleBatchBuffer;
import edu.washington.escience.myriad.column.Column;
import edu.washington.escience.myriad.column.ColumnFactory;
import edu.washington.escience.myriad.coordinator.catalog.Catalog;
import edu.washington.escience.myriad.coordinator.catalog.CatalogException;
import edu.washington.escience.myriad.operator.Operator;
import edu.washington.escience.myriad.parallel.Exchange.ExchangePairID;
import edu.washington.escience.myriad.parallel.Worker.MessageWrapper;
import edu.washington.escience.myriad.proto.ControlProto.ControlMessage;
import edu.washington.escience.myriad.proto.DataProto.ColumnMessage;
import edu.washington.escience.myriad.proto.DataProto.DataMessage;
import edu.washington.escience.myriad.proto.DataProto.DataMessage.DataMessageType;
import edu.washington.escience.myriad.proto.TransportProto.TransportMessage;
import edu.washington.escience.myriad.util.IPCUtils;

public final class Server {

  /** A short amount of time to sleep waiting for network events. */
  public static final int SHORT_SLEEP_MILLIS = 100;

  private final class MessageProcessor extends Thread {
    /** Whether the server has been stopped. */
    private volatile boolean stopped = false;

    @Override
    public void run() {

      TERMINATE_MESSAGE_PROCESSING : while (!stopped) {
        MessageWrapper mw = null;
        try {
          mw = messageQueue.poll(SHORT_SLEEP_MILLIS, TimeUnit.MILLISECONDS);
          if (mw == null) {
            continue;
          }
        } catch (final InterruptedException e) {
          e.printStackTrace();
          break TERMINATE_MESSAGE_PROCESSING;
        }
        final TransportMessage m = mw.message;
        final int senderID = mw.senderID;

        switch (m.getType()) {
          case DATA:

            final DataMessage data = m.getData();
            final ExchangePairID exchangePairID = ExchangePairID.fromExisting(data.getOperatorID());
            final Schema operatorSchema = exchangeSchema.get(exchangePairID);
            if (data.getType() == DataMessageType.EOS) {
              receiveData(new ExchangeData(exchangePairID, senderID, operatorSchema, 0));
            } else if (data.getType() == DataMessageType.EOI) {
              receiveData(new ExchangeData(exchangePairID, senderID, operatorSchema, 1));
            } else {
              final List<ColumnMessage> columnMessages = data.getColumnsList();
              final Column<?>[] columnArray = new Column[columnMessages.size()];
              int idx = 0;
              for (final ColumnMessage cm : columnMessages) {
                columnArray[idx++] = ColumnFactory.columnFromColumnMessage(cm);
              }
              final List<Column<?>> columns = Arrays.asList(columnArray);

              receiveData((new ExchangeData(exchangePairID, senderID, columns, operatorSchema, columnMessages.get(0)
                  .getNumTuples())));
            }
            break;
          case CONTROL:
            final ControlMessage controlM = m.getControl();
            final Long queryId = controlM.getQueryId();
            switch (controlM.getType()) {
              case QUERY_READY_TO_EXECUTE:
                queryReceivedByWorker(queryId, senderID);
                break;
              case WORKER_ALIVE:
                aliveWorkers.add(senderID);
                break;
              case QUERY_COMPLETE:
                HashMap<Integer, Integer> workersAssigned = workersAssignedToQuery.get(queryId);
                if (!workersAssigned.containsKey(senderID)) {
                  LOGGER.warn("Got a QUERY_COMPLETE message from worker " + senderID + " who is not assigned to query"
                      + queryId);
                  return;
                }
                workersAssigned.remove(senderID);
                break;
              case DISCONNECT:
                /* TODO */
                break;
              case CONNECT:
              case SHUTDOWN:
              case START_QUERY:
                throw new RuntimeException("Unexpected control message received at server: " + controlM.toString());
            }
            break;
          case QUERY:
            throw new RuntimeException("Unexpected query message received at server");
        }

      }

    }

    /** Tell the MessageProcessor to stop running after the next message is received. */
    public void setStopped() {
      stopped = true;
    }
  }

  /** Time constant. */
  private static final int ONE_SEC_IN_MILLIS = 1000;
  /** Time constant. */
  private static final int ONE_MIN_IN_MILLIS = 60 * ONE_SEC_IN_MILLIS;
  /** Time constant. */
  private static final int ONE_HR_IN_MILLIS = 60 * ONE_MIN_IN_MILLIS;

  static final String usage = "Usage: Server catalogFile [-explain] [-f queryFile]";
  /** The logger for this class. */
  private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(Server.class.getName());

  public static void main(final String[] args) throws IOException {
    Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.SEVERE);
    Logger.getLogger("com.almworks.sqlite4java.Internal").setLevel(Level.SEVERE);

    if (args.length < 1) {
      LOGGER.error(usage);
      System.exit(-1);
    }

    final String catalogName = args[0];
    final Server server;
    try {
      server = new Server(catalogName);
    } catch (CatalogException e) {
      throw new IOException(e);
    }

    LOGGER.debug("Workers are: ");
    for (final Entry<Integer, SocketInfo> w : server.workers.entrySet()) {
      LOGGER.debug(w.getKey() + ":  " + w.getValue().getHost() + ":" + w.getValue().getPort());
    }

    server.start();
    LOGGER.debug("Server started.");
  }

  private final ConcurrentHashMap<Integer, SocketInfo> workers;
  private final ConcurrentHashMap<Long, HashMap<Integer, Integer>> workersAssignedToQuery;
  private final Set<Integer> aliveWorkers;
  private final ConcurrentHashMap<Long, BitSet> workersReceivedQuery;

  /**
   * The I/O buffer, all the ExchangeMessages sent to the server are buffered here.
   */
  protected final ConcurrentHashMap<ExchangePairID, LinkedBlockingQueue<ExchangeData>> dataBuffer;

  protected final LinkedBlockingQueue<MessageWrapper> messageQueue;

  protected final ConcurrentHashMap<ExchangePairID, Schema> exchangeSchema;

  private final IPCConnectionPool connectionPool;

  protected final MessageProcessor messageProcessor;

  protected boolean interactive = true;

  public static final String SYSTEM_NAME = "Myriad";

  /** The Catalog stores the metadata about the Myria instance. */
  private final Catalog catalog;

  /**
   * Construct a server object, with configuration stored in the specified catalog file.
   * 
   * @param catalogFileName the name of the file containing the catalog.
   * @throws FileNotFoundException the specified file not found.
   * @throws CatalogException if there is an error reading from the Catalog.
   */
  public Server(final String catalogFileName) throws FileNotFoundException, CatalogException {
    catalog = Catalog.open(catalogFileName);

    /* Get the master socket info */
    List<SocketInfo> masters = catalog.getMasters();
    if (masters.size() != 1) {
      throw new RuntimeException("Unexpected number of masters: expected 1, got " + masters.size());
    }
    final SocketInfo masterSocketInfo = masters.get(MyriaConstants.MASTER_ID);

    workers = new ConcurrentHashMap<Integer, SocketInfo>(catalog.getWorkers());
    aliveWorkers = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    dataBuffer = new ConcurrentHashMap<ExchangePairID, LinkedBlockingQueue<ExchangeData>>();
    messageQueue = new LinkedBlockingQueue<MessageWrapper>();
    exchangeSchema = new ConcurrentHashMap<ExchangePairID, Schema>();

    final Map<Integer, SocketInfo> computingUnits = new HashMap<Integer, SocketInfo>(workers);
    computingUnits.put(MyriaConstants.MASTER_ID, masterSocketInfo);

    connectionPool = new IPCConnectionPool(MyriaConstants.MASTER_ID, computingUnits, messageQueue);
    messageProcessor = new MessageProcessor();
    workersAssignedToQuery = new ConcurrentHashMap<Long, HashMap<Integer, Integer>>();
    workersReceivedQuery = new ConcurrentHashMap<Long, BitSet>();
  }

  public void dispatchWorkerQueryPlans(final Long queryId, final Map<Integer, Operator[]> plans) throws IOException {
    final HashMap<Integer, Integer> setOfWorkers = new HashMap<Integer, Integer>(plans.size());
    workersAssignedToQuery.put(queryId, setOfWorkers);
    workersReceivedQuery.put(queryId, new BitSet(setOfWorkers.size()));

    int workerIdx = 0;
    for (final Map.Entry<Integer, Operator[]> e : plans.entrySet()) {
      final Integer workerID = e.getKey();
      setOfWorkers.put(workerID, workerIdx++);
      while (!aliveWorkers.contains(workerID)) {
        try {
          Thread.sleep(SHORT_SLEEP_MILLIS);
        } catch (InterruptedException e1) {
          Thread.currentThread().interrupt();
        }
      }
      getConnectionPool().sendShortMessage(workerID, IPCUtils.queryMessage(queryId, e.getValue()));
    }
  }

  /**
   * @return the network Connection Pool used by the Server.
   */
  public IPCConnectionPool getConnectionPool() {
    return connectionPool;
  }

  protected void queryReceivedByWorker(final Long queryId, final int workerId) {
    final BitSet workersReceived = workersReceivedQuery.get(queryId);
    final HashMap<Integer, Integer> workersAssigned = workersAssignedToQuery.get(queryId);
    final int workerIdx = workersAssigned.get(workerId);
    workersReceived.set(workerIdx);
  }

  /**
   * This method should be called when a data item is received.
   * 
   * @param data the data that was received.
   */
  public void receiveData(final ExchangeData data) {

    LinkedBlockingQueue<ExchangeData> q = null;
    q = dataBuffer.get(data.getOperatorID());
    if (data instanceof ExchangeData) {
      q.offer(data);
    }
  }

  public boolean queryCompleted(final Long queryId) {
    if (workersAssignedToQuery.containsKey(queryId)) {
      return workersAssignedToQuery.get(queryId).isEmpty();
    }
    return true;
  }

  /**
   * Shutdown all the threads doing work on this server.
   */
  public void shutdown() {
    messageProcessor.setStopped();
    LOGGER.debug(SYSTEM_NAME + " is going to shutdown");
    LOGGER.debug("Send shutdown requests to the workers, please wait");
    for (int workerId : aliveWorkers) {
      LOGGER.debug("Shutting down #" + workerId);

      final ChannelFuture cf = getConnectionPool().sendShortMessage(workerId, IPCUtils.CONTROL_SHUTDOWN);
      if (cf == null) {
        LOGGER.error("Fail to connect the worker: " + workerId + ". Continue cleaning");
      } else {
        cf.awaitUninterruptibly();
        LOGGER.debug("Done for worker #" + workerId);
      }
    }
    connectionPool.shutdown().awaitUninterruptibly();
    catalog.close();
    LOGGER.debug("Bye");
  }

  /**
   * Start all the threads that do work for the server.
   */
  public void start() {
    connectionPool.start();
    messageProcessor.start();
  }

  /**
   * This starts a query from the server using the query plan rooted by the given Consumer.
   * 
   * @param queryId the id of this query.
   * @param serverPlan the query plan to be executed.
   * @return @return the collected tuples gathered by the given Consumer.
   * @throws DbException if there are errors executing the serverPlan operators.
   */
  public TupleBatchBuffer startServerQuery(final Long queryId, final CollectConsumer serverPlan) throws DbException {
    final BitSet workersReceived = workersReceivedQuery.get(queryId);
    final HashMap<Integer, Integer> workersAssigned = workersAssignedToQuery.get(queryId);

    /* Can't progress until all assigned workers are alive. */
    while (!aliveWorkers.containsAll(workersAssigned.keySet())) {
      try {
        Thread.sleep(SHORT_SLEEP_MILLIS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    /* Can't progress until all assigned workers have received the query. */
    while (workersReceived.nextClearBit(0) < workersAssigned.size()) {
      try {
        Thread.sleep(SHORT_SLEEP_MILLIS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final LinkedBlockingQueue<ExchangeData> buffer = new LinkedBlockingQueue<ExchangeData>();
    exchangeSchema.put(serverPlan.getOperatorID(), serverPlan.getSchema());
    dataBuffer.put(serverPlan.getOperatorID(), buffer);
    serverPlan.setInputBuffer(buffer);

    final Schema schema = serverPlan.getSchema();

    String names = "";
    for (int i = 0; i < schema.numColumns(); i++) {
      names += schema.getColumnName(i) + "\t";
    }

    if (LOGGER.isDebugEnabled()) {
      final StringBuilder sb = new StringBuilder();
      sb.append(names).append('\n');
      for (int i = 0; i < names.length() + schema.numColumns() * 4; i++) {
        sb.append("-");
      }
      sb.append("");
      LOGGER.debug(sb.toString());
    }

    final Date start = new Date();
    serverPlan.open();

    startWorkerQuery(queryId);

    final TupleBatchBuffer outBufferForTesting = new TupleBatchBuffer(serverPlan.getSchema());
    int cnt = 0;
    TupleBatch tup = null;
    while (!serverPlan.eos()) {
      while ((tup = serverPlan.next()) != null) {
        // NOTICE: the next line needs to be removed when running on a large dataset!
        tup.compactInto(outBufferForTesting);
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug(tup.toString());
        }
        cnt += tup.numTuples();
      }
      if (serverPlan.eoi()) {
        serverPlan.setEOI(false);
      }
    }

    serverPlan.close();
    dataBuffer.remove(serverPlan.getOperatorID());
    final Date end = new Date();
    LOGGER.info("Number of results: " + cnt);
    int elapse = (int) (end.getTime() - start.getTime());
    final int hour = elapse / ONE_HR_IN_MILLIS;
    elapse -= hour * ONE_HR_IN_MILLIS;
    final int minute = elapse / ONE_MIN_IN_MILLIS;
    elapse -= minute * ONE_MIN_IN_MILLIS;
    final int second = elapse / ONE_SEC_IN_MILLIS;
    elapse -= second * ONE_SEC_IN_MILLIS;

    LOGGER.info(String.format("Time elapsed: %1$dh%2$dm%3$ds.%4$03d", hour, minute, second, elapse));
    return outBufferForTesting;
  }

  /**
   * This starts a query from the server using the query plan rooted by the given Producer.
   * 
   * @param queryId the id of this query.
   * @param serverPlan the query plan to be executed.
   * @throws DbException if there are errors executing the serverPlan operators.
   */
  public void startServerQuery(final Long queryId, final Producer serverPlan) throws DbException {
    final BitSet workersReceived = workersReceivedQuery.get(queryId);
    final HashMap<Integer, Integer> workersAssigned = workersAssignedToQuery.get(queryId);
    /* Can't progress until all assigned workers are alive. */
    while (!aliveWorkers.containsAll(workersAssigned.keySet())) {
      try {
        Thread.sleep(SHORT_SLEEP_MILLIS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    /* Can't progress until all assigned workers have received the query. */
    while (workersReceived.nextClearBit(0) < workersAssigned.size()) {
      try {
        Thread.sleep(SHORT_SLEEP_MILLIS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final Date start = new Date();

    serverPlan.open();

    startWorkerQuery(queryId);

    while (serverPlan.next() != null) {
      assert true; /* Do nothing. */
    }

    serverPlan.close();

    final Date end = new Date();
    int elapse = (int) (end.getTime() - start.getTime());
    final int hour = elapse / ONE_HR_IN_MILLIS;
    elapse -= hour * ONE_HR_IN_MILLIS;
    final int minute = elapse / ONE_MIN_IN_MILLIS;
    elapse -= minute * ONE_MIN_IN_MILLIS;
    final int second = elapse / ONE_SEC_IN_MILLIS;
    elapse -= second * ONE_SEC_IN_MILLIS;

    LOGGER.debug(String.format("Time elapsed: %1$dh%2$dm%3$ds.%4$03d", hour, minute, second, elapse));
  }

  /**
   * Tells all the workers to start the given query.
   * 
   * @param queryId the id of the query to be started.
   */
  protected void startWorkerQuery(final Long queryId) {
    final HashMap<Integer, Integer> workersAssigned = workersAssignedToQuery.get(queryId);
    for (final Entry<Integer, Integer> entry : workersAssigned.entrySet()) {
      getConnectionPool().sendShortMessage(entry.getKey(), IPCUtils.startQueryTM(MyriaConstants.MASTER_ID, queryId));
    }
  }

  public Set<Integer> getAliveWorkers() {
    return aliveWorkers;
  }

  public Map<Integer, SocketInfo> getWorkers() {
    return workers;
  }

  /**
   * Insert the given query into the Catalog, dispatch the query to the workers, and return its query ID. This is useful
   * for receiving queries from an external interface (e.g., the REST API).
   * 
   * @param rawQuery the raw user-defined query. E.g., the source Datalog program.
   * @param logicalRa the logical relational algebra of the compiled plan.
   * @param plans the physical parallel plan fragments for each worker.
   * @return the query ID assigned to this query.
   * @throws CatalogException if there is an error in the Catalog.
   * @throws IOException if there is an error communicating with the workers.
   */
  public Long startQuery(final String rawQuery, final String logicalRa, final Map<Integer, Operator[]> plans)
      throws CatalogException, IOException {
    final Long queryId = catalog.newQuery(rawQuery, logicalRa);
    dispatchWorkerQueryPlans(queryId, plans);
    startWorkerQuery(queryId);
    return queryId;
  }

  /**
   * @param relationName the name of the desired relation.
   * @return the schema of the specified relation, or null if not found.
   * @throws CatalogException if there is an error getting the Schema out of the catalog.
   */
  public Schema getSchema(final String relationName) throws CatalogException {
    return catalog.getSchema(relationName);
  }
}