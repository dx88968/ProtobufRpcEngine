/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package Engine;

import core.RPC;
import core.RPC.RPCServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author DX
 */
public class ServerObjectContainer {
    private InetSocketAddress ip;
    private int numHandlers = 1;
    private int numReaders = 1;
    private RPCServer server;
    private Map<InetSocketAddress,ServerObjectContainer> containers
            =new HashMap();
    
    
    public ServerObjectContainer(InetSocketAddress ip) throws IOException{
        this(ip,1,1);
    }
    
    public ServerObjectContainer(InetSocketAddress ip,int numhandlers,int numreaders) throws IOException{
        this.ip=ip;
        this.numHandlers=numhandlers;
        this.numReaders=numreaders;
        server=(RPCServer) new RPC.Builder().setBindAddress(ip.getAddress().getHostAddress()).setPort(this.ip.getPort())
                    .setnumReaders(numReaders).setNumHandlers(numHandlers).build();
    }
    
    public void registerProtocolAndImpl(Class<?> protocolClass, 
       Object protocolImpl) throws IOException{
        server.registerProtocolImpl(protocolClass.getName(), protocolImpl);
    }
    
    public void enable() throws IOException{
        server.startListen();
    }
    
    public void disable(){
        server.stop();
    }
}
