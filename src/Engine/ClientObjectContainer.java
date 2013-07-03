/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package Engine;

import core.EmptyProtocolPB;
import core.ProtobufRpcEngine;
import core.RPC;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import javax.net.SocketFactory;

/**
 *
 * @author DX
 */
public class ClientObjectContainer {
    
    public ClientObjectContainer(){
        RPC.setProtocolEngine(EmptyProtocolPB.class, ProtobufRpcEngine.class);
    }
    
    public <T> T getProtocolProxy (Class<T> protocol,
                                InetSocketAddress addr, int rpcTimeout) throws IOException {   
        return RPC.getProtocolProxy(protocol, addr, SocketFactory.getDefault(), rpcTimeout).getProxy();
    }
    
}
