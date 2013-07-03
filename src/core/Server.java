/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package core;
import CoreProtos.RpcHeaderProtos.RpcRequestHeaderProto;
import CoreProtos.RpcHeaderProtos.RpcResponseHeaderProto;
import core.ProtobufRpcEngine.RpcRequestWrapper;
import core.ProtobufRpcEngine.RpcResponseWrapper;
import io.Writable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author DX
 */
public abstract class Server {
    
    public static final ByteBuffer HEADER = ByteBuffer.wrap("hrpc".getBytes());
    private static final int NIO_BUFFER_LIMIT=8*1024;
    private BlockingQueue<Call> callQueue;
    private List<Connection> connectionList = 
    Collections.synchronizedList(new LinkedList<Connection>());
    private int numConnections = 0;
    private static final ThreadLocal<Server> SERVER = new ThreadLocal<>();
    private static final ThreadLocal<Call> CurCall = new ThreadLocal<>();
    private int maxQueueSize=1000;
    private boolean running=true;
    private int numReaders=1;
    Handler[] handlers;

    public void setMaxQueueSize(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
    }

    public void setNumReaders(int numReaders) {
        this.numReaders = numReaders;
    }

    public void setNumHandlers(int numHandlers) {
        this.numHandlers = numHandlers;
    }

    private int numHandlers=1;
    Listener listener;
    Responder responder;
    InetSocketAddress ip;
    
    public Server(){
        this.callQueue  = new LinkedBlockingQueue(maxQueueSize); 
    }
    
    public void startListen() throws IOException{
        listener = new Listener();
        responder=new Responder();
        responder.start();
        listener.start();
        handlers = new Handler[numHandlers];
        for (int i = 0; i < numHandlers; i++) {
          handlers[i] = new Handler(i);
          handlers[i].start();
        }
    }
    
      /** Stops the service.  No new calls will be handled after this is called. */
  public synchronized void stop() {
    running = false;
    if (handlers != null) {
      for (int i = 0; i < numHandlers; i++) {
        if (handlers[i] != null) {
          handlers[i].interrupt();
        }
      }
    }
    listener.interrupt();
    listener.doStop();
    responder.interrupt();
    notifyAll();
  }
    
    public void setIP(InetSocketAddress ip){
        this.ip=ip;
    }
    
    private synchronized void terminateCall(String CallMark){
        for(int i = 0; i < numHandlers; i++){
            if(handlers[i].getCurrentCallMark() == null ? CallMark == null : handlers[i].getCurrentCallMark().equals(CallMark)){
                handlers[i].stop();
                handlers[i] = new Handler(i);
                handlers[i].start();
                break;
            }
        }
    }
    
    /** A call queued for handling. */
  private static class Call {

        public Writable getRpcRequest() {
            return rpcRequest;
        }

        public Connection getConnection() {
            return connection;
        }

        public ByteBuffer getRpcResponse() {
            return rpcResponse;
        }
        
    private final Writable rpcRequest;    // Serialized Rpc request from client
    private final Connection connection;  // connection to client
    private long timestamp;               // time received when response is null
    private int callid;                                      // time served when response is not null
    private String callmark="";
    
        public void setCallid(int callid) {
            this.callid = callid;
        }
   

        public String getCallmark() {
            return callmark;
        }

        public void setCallmark(String callmark) {
            this.callmark = callmark;
        }

        public int getCallid() {
            return callid;
        }

        
    private ByteBuffer rpcResponse;       // the response for this call

    public Call(Writable param, Connection connection) {
          rpcRequest=param;
          this.connection=connection;
    }
    
    
    @Override
    public String toString() {
      return rpcRequest.toString() + " from " + connection.toString();
    }

    public void setResponse(ByteBuffer response) {
      this.rpcResponse = response;
    }
  }
  
  private class Responder extends Thread {
    private final Selector writeSelector;
    private int pending;         // connections waiting to register
    
    final static int PURGE_INTERVAL = 900000; // 15mins

    Responder() throws IOException {
      this.setName("IPC Server Responder");
      this.setDaemon(true);
      writeSelector = Selector.open(); // create a selector
      pending = 0;
    }

    @Override
    public void run() {
      SERVER.set(Server.this);
      try {
        doRunLoop();
      } finally {
        try {
          writeSelector.close();
        } catch (IOException ioe) {
          System.out.println("Couldn't close write selector in " + this.getName());
        }
      }
    }
    
    private void doRunLoop() {
      long lastPurgeTime = 0;   // last check for old calls.

      while (running) {
        try {
          waitPending();     // If a channel is being registered, wait.
          writeSelector.select(PURGE_INTERVAL);
          Iterator<SelectionKey> iter = writeSelector.selectedKeys().iterator();
          while (iter.hasNext()) {
            SelectionKey key = iter.next();
            iter.remove();
            try {
              if (key.isValid() && key.isWritable()) {
                  doAsyncWrite(key);
              }
            } catch (IOException e) {
              System.out.println(getName() + ": doAsyncWrite threw exception " + e);
            }
          }
          // If there were some calls that have not been sent out for a
          // long time, discard them.
          //
          ArrayList<Call> calls;
          
          // get the list of channels from list of keys.
          synchronized (writeSelector.keys()) {
            calls = new ArrayList(writeSelector.keys().size());
            iter = writeSelector.keys().iterator();
            while (iter.hasNext()) {
              SelectionKey key = iter.next();
              Call call = (Call)key.attachment();
              if (call != null && key.channel() == call.connection.channel) { 
                calls.add(call);
              }
            }
          }
          
          for(Call call : calls) {
            try {
              doPurge(call,System.currentTimeMillis());
            } catch (IOException e) {
              System.out.println("Error in purging old calls " + e);
            }
          }
        } catch (OutOfMemoryError e) {
          //
          // we can run out of memory if we have too many threads
          // log the event and sleep for a minute and give
          // some thread(s) a chance to finish
          //
          System.out.println("Out of Memory in server select");
          try { Thread.sleep(60000); } catch (Exception ie) {}
        } catch (Exception e) {
          System.out.println("Exception in Responder");
        }
      }
    }

    private void doAsyncWrite(SelectionKey key) throws IOException {
      Call call = (Call)key.attachment();
      if (call == null) {
        return;
      }
      if (key.channel() != call.connection.channel) {
        throw new IOException("doAsyncWrite: bad channel");
      }

      synchronized(call.connection.responseQueue) {
        if (processResponse(call.connection.responseQueue, false)) {
          try {
            key.interestOps(0);
          } catch (CancelledKeyException e) {
            /* The Listener/reader might have closed the socket.
             * We don't explicitly cancel the key, so not sure if this will
             * ever fire.
             * This warning could be removed.
             */
            System.out.println("Exception while changing ops : " + e);
          }
        }
      }
    }

    //
    // Remove calls that have been pending in the responseQueue 
    // for a long time.
    //
    private void doPurge(Call call, long now) throws IOException {
      LinkedList<Call> responseQueue = call.connection.responseQueue;
      synchronized (responseQueue) {
        Iterator<Call> iter = responseQueue.listIterator(0);
        while (iter.hasNext()) {
          call = iter.next();
          if (now > call.timestamp + PURGE_INTERVAL) {
            closeConnection(call.connection);
            break;
          }
        }
      }
    }

    // Processes one response. Returns true if there are no more pending
    // data for this channel.
    //
    private boolean processResponse(LinkedList<Call> responseQueue,
                                    boolean inHandler) throws IOException {
      boolean error = true;
      boolean done = false;       // there is more data for this channel.
      int numElements;
      Call call = null;
      try {
        synchronized (responseQueue) {
          //
          // If there are no items for this channel, then we are done
          //
          numElements = responseQueue.size();
          if (numElements == 0) {
            error = false;
            return true;              // no more data for this channel.
          }
          //
          // Extract the first call
          //
          call = responseQueue.removeFirst();
          SocketChannel channel = call.connection.channel;
          
          //
          // Send as much data as we can in the non-blocking fashion
          //
          int numBytes = channelWrite(channel, call.rpcResponse);
          if (numBytes < 0) {
            return true;
          }
          if (!call.rpcResponse.hasRemaining()) {
            //Clear out the response buffer so it can be collected
            call.rpcResponse = null;
            call.connection.decRpcCount();
            if (numElements == 1) {    // last call fully processes.
              done = true;             // no more data for this channel.
            } else {
              done = false;            // more calls pending to be sent.
            }
          } else {
            //
            // If we were unable to write the entire response out, then 
            // insert in Selector queue. 
            //
            call.connection.responseQueue.addFirst(call);
            
            if (inHandler) {
              // set the serve time when the response has to be sent later
              call.timestamp = System.currentTimeMillis();
              
              incPending();
              try {
                // Wakeup the thread blocked on select, only then can the call 
                // to channel.register() complete.
                writeSelector.wakeup();
                channel.register(writeSelector, SelectionKey.OP_WRITE, call);
              } catch (ClosedChannelException e) {
                //Its ok. channel might be closed else where.
                done = true;
              } finally {
                decPending();
              }
            }
          }
          error = false;              // everything went off well
        }
      } finally {
        if (error && call != null) {
          System.out.println(getName()+", call " + call + ": output error");
          done = true;               // error. no more data for this channel.
          closeConnection(call.connection);
        }
      }
      return done;
    }

    //
    // Enqueue a response from the application.
    //
    void doRespond(Call call) throws IOException {
      synchronized (call.connection.responseQueue) {
        call.connection.responseQueue.addLast(call);
        if (call.connection.responseQueue.size() == 1) {
          processResponse(call.connection.responseQueue, true);
        }
      }
    }

    private synchronized void incPending() {   // call waiting to be enqueued.
      pending++;
    }

    private synchronized void decPending() { // call done enqueueing.
      pending--;
      notify();
    }

    private synchronized void waitPending() throws InterruptedException {
      while (pending > 0) {
        wait();
      }
    }
  }

    
       /** Listens on the socket. Creates jobs for the handler threads*/
  private class Listener extends Thread {
    
    
    private ServerSocketChannel acceptChannel = null; //the accept channel
    private Selector selector = null; //the selector that we use for the server
    private Reader[] readers = null;
    private int currentReader = 0;
    private InetSocketAddress address; //the address we bind at
    private Random rand = new Random();
    private long lastCleanupRunTime = 0; //the last time when a cleanup connec-
                                         //-tion (for idle connections) ran
    private long cleanupInterval = 10000; //the minimum interval between 
                                          //two cleanup runs
    
    
    public Listener() throws IOException {
        if(ip==null) {
            address = new InetSocketAddress("0.0.0.0", 9007);
        }else{
            address=ip;
        }
      // Create a new server socket and set to non blocking mode
      acceptChannel = ServerSocketChannel.open();
      acceptChannel.configureBlocking(false);

      // Bind the server socket to the local host and port
      bind(acceptChannel.socket(), address, 100);
      int port = acceptChannel.socket().getLocalPort(); //Could be an ephemeral port
      // create a selector;
      selector= Selector.open();
      readers = new Reader[numReaders];
      for (int i = 0; i < numReaders; i++) {
        Reader reader = new Reader(
            "Socket Reader #" + (i + 1) + " for port " + port);
        readers[i] = reader;
        reader.start();
      }

      // Register accepts on the server socket with the selector.
      acceptChannel.register(selector, SelectionKey.OP_ACCEPT);
      this.setName("IPC Server listener on " + port);
      this.setDaemon(true);
    }
    
        synchronized void doStop() {
            if (selector != null) {
              selector.wakeup();
              Thread.yield();
            }
            if (acceptChannel != null) {
              try {
                acceptChannel.socket().close();
              } catch (IOException e) {
               
              }
            }
            for (Reader r : readers) {
              r.shutdown();
            }
          }
    
    void doRead(SelectionKey key) throws InterruptedException {
        Connection c=(Connection) key.attachment();
        int count=0;
          try {
                count = c.readAndProcess();
            } catch (InterruptedException ex) {
                throw ex;
            } catch (IOException ex) {
              closeConnection(c);
              c = null;
          }
        if(count<0){
            closeConnection(c);
            c = null;
        }else{
            c.setLastContact(System.currentTimeMillis());
        }
    }  
    
    private class Reader extends Thread {
      private volatile boolean adding = false;
      private final Selector readSelector;

      Reader(String name) throws IOException {
        super(name);

        this.readSelector = Selector.open();
      }
      
      @Override
      public void run() {
        try {
          doRunLoop();
        } finally {
          try {
            readSelector.close();
          } catch (IOException ioe) {
            ioe.printStackTrace();
          }
        }
      }

      private synchronized void doRunLoop() {
        while (true) {
          SelectionKey key = null;
          try {
            readSelector.select();
            while (adding) {
              this.wait(1000);
            }              

            Iterator<SelectionKey> iter = readSelector.selectedKeys().iterator();
            while (iter.hasNext()) {
              key = iter.next();
              iter.remove();
              if (key.isValid()) {
                if (key.isReadable()) {
                  doRead(key);
                }
              }
              key = null;
            }
          } catch (Exception e) {
           
        }
      }
     }
      
      
    /**
       * This gets reader into the state that waits for the new channel
       * to be registered with readSelector. If it was waiting in select()
       * the thread will be woken up, otherwise whenever select() is called
       * it will return even if there is nothing to read and wait
       * in while(adding) for finishAdd call
       */
      public void startAdd() {
        adding = true;
        readSelector.wakeup();
      }
      
      public synchronized SelectionKey registerChannel(SocketChannel channel)
                                                          throws IOException {
          return channel.register(readSelector, SelectionKey.OP_READ);
      }

      public synchronized void finishAdd() {
        adding = false;
        this.notify();        
      }

      void shutdown() {
        readSelector.wakeup();
        try {
          join();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    }
    
    @Override
    public void run() {
      while (true) {
        SelectionKey key = null;
        try {
          getSelector().select();
          Iterator<SelectionKey> iter = getSelector().selectedKeys().iterator();
          while (iter.hasNext()) {
            key = iter.next();
            iter.remove();
            try {
              if (key.isValid()) {
                if (key.isAcceptable())
                  doAccept(key);
              }
            } catch (IOException e) {
            }
            key = null;
          }
        } catch (OutOfMemoryError e) {
          
          try { Thread.sleep(60000); } catch (Exception ie) {}
        } catch (Exception e) {
          
        }
       }
      
    }
    
    void doAccept(SelectionKey key) throws IOException,  OutOfMemoryError {
      ServerSocketChannel server = (ServerSocketChannel) key.channel();
      SocketChannel channel;
      while ((channel = server.accept()) != null) {

        channel.configureBlocking(false);
        
        Reader reader = getReader();
        try {
          reader.startAdd();
          SelectionKey readKey = reader.registerChannel(channel);
          Connection c=new Connection(channel);
          readKey.attach(c);
          synchronized (connectionList) {
            connectionList.add(numConnections, c);
            numConnections++;
          }   
        } finally {
          reader.finishAdd(); 
        }
      }
    }
    
    synchronized Selector getSelector() { return selector; }
    // The method that will return the next reader to work with
    // Simplistic implementation of round robin for now
    Reader getReader() {
      currentReader = (currentReader + 1) % readers.length;
      return readers[currentReader];
    }
 }
  public static void bind(ServerSocket socket, InetSocketAddress address, 
      int backlog) throws IOException {
        socket.bind(address, backlog);
  }
  
    private int channelRead(ReadableByteChannel channel, 
                          ByteBuffer buffer) throws IOException {
    
    int count = (buffer.remaining() <= NIO_BUFFER_LIMIT) ?
                channel.read(buffer) : channelIO(channel, null, buffer);
    return count;
  }
  
  private int channelWrite(WritableByteChannel channel, 
                           ByteBuffer buffer) throws IOException {
    
    int count =  (buffer.remaining() <= NIO_BUFFER_LIMIT) ?
                 channel.write(buffer) : channelIO(null, channel, buffer);
    return count;
  }
  
  private static int channelIO(ReadableByteChannel readCh, 
                               WritableByteChannel writeCh,
                               ByteBuffer buf) throws IOException {
    
    int originalLimit = buf.limit();
    int initialRemaining = buf.remaining();
    int ret = 0;
    
    while (buf.remaining() > 0) {
      try {
        int ioSize = Math.min(buf.remaining(), NIO_BUFFER_LIMIT);
        buf.limit(buf.position() + ioSize);
        
        ret = (readCh == null) ? writeCh.write(buf) : readCh.read(buf); 
        
        if (ret < ioSize) {
          break;
        }

      } finally {
        buf.limit(originalLimit);        
      }
    }

    int nBytes = initialRemaining - buf.remaining(); 
    return (nBytes > 0) ? nBytes : ret;
  }
  
  /** Handles queued calls . */
  private class Handler extends Thread {
      String currentCallMark="-1";

        public String getCurrentCallMark() {
            return currentCallMark;
        }
      
    public Handler(int instanceNumber) {
      this.setDaemon(true);
      this.setName("IPC Server handler "+ instanceNumber);
    }

    @Override
    public void run() {
        SERVER.set(Server.this);
        while(true){
            try {
                final Call call = callQueue.take();
                currentCallMark=call.getCallmark();
                CurCall.set(call);
                RpcResponseHeaderProto header=RpcResponseHeaderProto.newBuilder()
                        .setCallid(call.getCallid()).build();
                RpcResponseWrapper value = (RpcResponseWrapper) call(call.getRpcRequest());
                CurCall.set(null);
                synchronized (call.connection.responseQueue) {
                    ByteArrayOutputStream responseBuf = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(responseBuf);
                    out.writeInt(call.getCallid());
                    header.writeDelimitedTo(out);
                    value.write(out);
                    call.setResponse(ByteBuffer.wrap(responseBuf.toByteArray()));
                }
                responder.doRespond(call);
            } catch (InterruptedException | IOException ex) {
                Logger.getLogger(Server.class.getName()).log(Level.SEVERE, null, ex);
            }
            
        }
    }

  }
  
    public class Connection {
        
          private SocketChannel channel;
          private LinkedList<Call> responseQueue;
          private int rpcCount;
          private ByteBuffer dataLengthBuffer;
          private boolean connectionHeaderRead = false; 
          private ByteBuffer data;
          private long lastContact;
          
          public Connection(SocketChannel channel) {
            this.channel = channel;
            this.responseQueue = new LinkedList();
            this.data = null;
            this.dataLengthBuffer = ByteBuffer.allocate(4);
            
          } 
          
          /* Decrement the outstanding RPC count */
            private void decRpcCount() {
              rpcCount--;
            }

            /* Increment the outstanding RPC count */
            private void incRpcCount() {
              rpcCount++;
            }
            
            /* Return true if the connection has no outstanding rpc */
            private boolean isIdle() {
              return rpcCount == 0;
            }
          
        public int readAndProcess() throws IOException, InterruptedException{        
              while (true) {
                  /* Read at most one RPC. If the header is not read completely yet
                   * then iterate until we read first RPC or until there is no data left.
                   */    
                  int count = -1;
                  InetSocketAddress remoteAddr=(InetSocketAddress) channel.getRemoteAddress();
                  if (dataLengthBuffer.remaining() > 0) {
                    count = channelRead(channel, dataLengthBuffer);       
                    if (count < 0 || dataLengthBuffer.remaining() > 0) {
                          return count;
                      }
                  }
                  if (!connectionHeaderRead) {
                    //Every connection is expected to send the header.

                    dataLengthBuffer.flip();

                    if (!HEADER.equals(dataLengthBuffer)) {
                      //Warning is ok since this is not supposed to happen.
                      System.err.println("Missing or worng header");
                    }
                    //finish reading head
                    dataLengthBuffer.clear();

                    connectionHeaderRead = true;
                    continue;
                  }

                  if (data == null) {
                    dataLengthBuffer.flip();
                    int dataLength = dataLengthBuffer.getInt();
                    if (dataLength == Client.PING_CALL_ID) {
                        // covers the !useSasl too
                        dataLengthBuffer.clear();
                        return 0; // ping message
                      }
                    if(dataLength == Client.TERMINATE_CALL_ID){
                        dataLengthBuffer.clear();
                        channelRead(channel, dataLengthBuffer);
                        dataLengthBuffer.flip();
                        int callid=dataLengthBuffer.getInt();
                        String callMark=remoteAddr.toString()+callid;
                        terminateCall(callMark);
                        return 0;
                    }
                    if (dataLength < 0) {
                      return -1;
                    }
                    data = ByteBuffer.allocate(dataLength);
                  }

                  count = channelRead(channel, data);

                  if (data.remaining() == 0) {
                    dataLengthBuffer.clear();
                    data.flip();
                    DataInputStream dis =new DataInputStream(new ByteArrayInputStream(data.array()));
                    RpcRequestHeaderProto rpcHeader=RpcRequestHeaderProto.parseDelimitedFrom(dis);
                    RpcRequestWrapper request=new RpcRequestWrapper();
                    request.readFields(dis);
                    Call call=new Call(request,this);
                    String callMark=remoteAddr.toString()+rpcHeader.getCallid();
                    call.setCallid(rpcHeader.getCallid());
                    call.setCallmark(callMark);
                    callQueue.put(call);
                    incRpcCount();    
                    data=null;
                  }
                  return count;
              }
          }

        private synchronized void close() throws IOException {
            if (!channel.isOpen())
              return;
            try {channel.socket().shutdownOutput();} catch(Exception e) {
              System.out.println("Ignoring socket shutdown exception");
            }
            if (channel.isOpen()) {
              try {channel.close();} catch(Exception e) {}
            }
            try {channel.socket().close();} catch(Exception e) {}
          }

        private void setLastContact(long currentTimeMillis) {
            this.lastContact = lastContact;
        }
          
          
    }
    
  private void closeConnection(Connection connection) {
    synchronized (connectionList) {
      if (connectionList.remove(connection)) {
            numConnections--;
        }
    }
    try {
      connection.close();
    } catch (IOException e) {
    }
  }
  
  
   abstract Writable call(Writable request);
    
}
