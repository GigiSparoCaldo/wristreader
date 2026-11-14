package com.akemi.wristreader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Collections;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

public class SniSSLSocket extends SSLSocket {
    private final SSLSocket d;
    private final String sniHost;

    public SniSSLSocket(SSLSocket delegate, String sniHost) {
        this.d = delegate;
        this.sniHost = sniHost;
        enforceSni();
    }

    private void enforceSni() {
        if (sniHost == null || sniHost.isEmpty()) return;
        try {
            SSLParameters p = d.getSSLParameters();
            p.setServerNames(Collections.singletonList(new SNIHostName(sniHost)));
            d.setSSLParameters(p);
        } catch (Exception ignored) {}
        try {
            d.getClass().getMethod("setHostname", String.class).invoke(d, sniHost);
        } catch (Exception ignored) {}
    }

    public void setHostname(String host) {
        enforceSni();
    }

    public void setUseSessionTickets(boolean flag) {
        try { d.getClass().getMethod("setUseSessionTickets", boolean.class).invoke(d, flag); } catch (Exception ignored) {}
    }

    public void setAlpnProtocols(byte[] protocols) {
        try { d.getClass().getMethod("setAlpnProtocols", byte[].class).invoke(d, (Object) protocols); } catch (Exception ignored) {}
    }

    public byte[] getAlpnSelectedProtocol() {
        try { return (byte[]) d.getClass().getMethod("getAlpnSelectedProtocol").invoke(d); } catch (Exception e) { return null; }
    }

    @Override
    public void setSSLParameters(SSLParameters params) {
        if (sniHost != null && !sniHost.isEmpty()) {
            try { params.setServerNames(Collections.singletonList(new SNIHostName(sniHost))); } catch (Exception ignored) {}
        }
        d.setSSLParameters(params);
    }

    @Override public SSLParameters getSSLParameters() { return d.getSSLParameters(); }
    @Override public void startHandshake() throws IOException { enforceSni(); d.startHandshake(); }
    @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
    @Override public String[] getEnabledCipherSuites() { return d.getEnabledCipherSuites(); }
    @Override public void setEnabledCipherSuites(String[] s) { d.setEnabledCipherSuites(s); }
    @Override public String[] getSupportedProtocols() { return d.getSupportedProtocols(); }
    @Override public String[] getEnabledProtocols() { return d.getEnabledProtocols(); }
    @Override public void setEnabledProtocols(String[] p) { d.setEnabledProtocols(p); }
    @Override public SSLSession getSession() { return d.getSession(); }
    @Override public SSLSession getHandshakeSession() { return d.getHandshakeSession(); }
    @Override public void addHandshakeCompletedListener(HandshakeCompletedListener l) { d.addHandshakeCompletedListener(l); }
    @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener l) { d.removeHandshakeCompletedListener(l); }
    @Override public void setUseClientMode(boolean m) { d.setUseClientMode(m); }
    @Override public boolean getUseClientMode() { return d.getUseClientMode(); }
    @Override public void setNeedClientAuth(boolean n) { d.setNeedClientAuth(n); }
    @Override public boolean getNeedClientAuth() { return d.getNeedClientAuth(); }
    @Override public void setWantClientAuth(boolean w) { d.setWantClientAuth(w); }
    @Override public boolean getWantClientAuth() { return d.getWantClientAuth(); }
    @Override public void setEnableSessionCreation(boolean f) { d.setEnableSessionCreation(f); }
    @Override public boolean getEnableSessionCreation() { return d.getEnableSessionCreation(); }
    @Override public void connect(SocketAddress ep) throws IOException { d.connect(ep); }
    @Override public void connect(SocketAddress ep, int timeout) throws IOException { d.connect(ep, timeout); }
    @Override public void bind(SocketAddress bp) throws IOException { d.bind(bp); }
    @Override public InetAddress getInetAddress() { return d.getInetAddress(); }
    @Override public InetAddress getLocalAddress() { return d.getLocalAddress(); }
    @Override public int getPort() { return d.getPort(); }
    @Override public int getLocalPort() { return d.getLocalPort(); }
    @Override public SocketAddress getRemoteSocketAddress() { return d.getRemoteSocketAddress(); }
    @Override public SocketAddress getLocalSocketAddress() { return d.getLocalSocketAddress(); }
    @Override public InputStream getInputStream() throws IOException { return d.getInputStream(); }
    @Override public OutputStream getOutputStream() throws IOException { return d.getOutputStream(); }
    @Override public void setTcpNoDelay(boolean on) throws SocketException { d.setTcpNoDelay(on); }
    @Override public boolean getTcpNoDelay() throws SocketException { return d.getTcpNoDelay(); }
    @Override public void setSoLinger(boolean on, int linger) throws SocketException { d.setSoLinger(on, linger); }
    @Override public int getSoLinger() throws SocketException { return d.getSoLinger(); }
    @Override public void setSoTimeout(int timeout) throws SocketException { d.setSoTimeout(timeout); }
    @Override public int getSoTimeout() throws SocketException { return d.getSoTimeout(); }
    @Override public void setSendBufferSize(int size) throws SocketException { d.setSendBufferSize(size); }
    @Override public int getSendBufferSize() throws SocketException { return d.getSendBufferSize(); }
    @Override public void setReceiveBufferSize(int size) throws SocketException { d.setReceiveBufferSize(size); }
    @Override public int getReceiveBufferSize() throws SocketException { return d.getReceiveBufferSize(); }
    @Override public void setKeepAlive(boolean on) throws SocketException { d.setKeepAlive(on); }
    @Override public boolean getKeepAlive() throws SocketException { return d.getKeepAlive(); }
    @Override public synchronized void close() throws IOException { d.close(); }
    @Override public void shutdownInput() throws IOException { d.shutdownInput(); }
    @Override public void shutdownOutput() throws IOException { d.shutdownOutput(); }
    @Override public boolean isConnected() { return d.isConnected(); }
    @Override public boolean isBound() { return d.isBound(); }
    @Override public boolean isClosed() { return d.isClosed(); }
    @Override public boolean isInputShutdown() { return d.isInputShutdown(); }
    @Override public boolean isOutputShutdown() { return d.isOutputShutdown(); }
}
