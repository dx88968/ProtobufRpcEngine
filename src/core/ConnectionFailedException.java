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
public class ConnectionFailedException extends IOException {

    /**
     * Creates a new instance of
     * <code>ConnectionFailedException</code> without detail message.
     */
    public ConnectionFailedException() {
    }

    /**
     * Constructs an instance of
     * <code>ConnectionFailedException</code> with the specified detail message.
     *
     * @param msg the detail message.
     */
    public ConnectionFailedException(String msg) {
        super(msg);
    }
}
