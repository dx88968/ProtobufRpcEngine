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
class ConnectTimeoutException extends IOException {

    public ConnectTimeoutException(String message) {
        super(message);
    }
    
}
