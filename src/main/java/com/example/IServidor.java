// ===================== IServidor.java =====================
package com.example;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IServidor extends Remote {

    String getOponent(String jsonIn) throws RemoteException;

}