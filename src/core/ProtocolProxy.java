/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package core;

/**
 *
 * @author DX
 */
public class ProtocolProxy<T> {
    
    private T proxy;
    
    public ProtocolProxy(T proxy) {
        this.proxy = proxy;
      }
    
      /*
        * Get the proxy
        */
       public T getProxy() {
         return proxy;
       }
  
}
