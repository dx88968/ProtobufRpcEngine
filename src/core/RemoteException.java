/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package core;

import java.io.IOException;

/**
 *
 * @author DX
 */
class RemoteException extends IOException{
    
    public RemoteException(String message){
        super(message);
    }
    
    public RemoteException(String message,int code){
        super(message+"   code:"+code);
    }
    
}
