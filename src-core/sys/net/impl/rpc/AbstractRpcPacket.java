/*****************************************************************************
 * Copyright 2011-2012 INRIA
 * Copyright 2011-2012 Universidade Nova de Lisboa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *****************************************************************************/
package sys.net.impl.rpc;

import static sys.Sys.Sys;
import sys.net.api.Endpoint;
import sys.net.api.Message;
import sys.net.api.TransportConnection;
import sys.net.api.rpc.RpcEndpoint;
import sys.net.api.rpc.RpcHandle;
import sys.net.api.rpc.RpcHandler;
import sys.net.api.rpc.RpcMessage;
import sys.net.impl.AbstractMessage;
import sys.net.impl.NetworkingConstants;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoSerializable;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

abstract class AbstractRpcPacket extends AbstractMessage implements Message, RpcHandle, RpcEndpoint, KryoSerializable {

    long handlerId; // destination service handler
    long replyHandlerId; // reply handler, 0 = no reply expected, negative ->
                         // allow streaming replies...
    RpcMessage payload;

    int timeout;
    long timestamp;

    transient RpcHandler handler;
    transient RpcFactoryImpl fac;
    volatile transient AbstractRpcPacket reply;

    transient Endpoint remote;
    transient TransportConnection conn;

    transient int DEFAULT_TIMEOUT = NetworkingConstants.RPC_DEFAULT_TIMEOUT;

    transient boolean failed = false;
    transient Throwable failureCause;

    protected AbstractRpcPacket() {
        this.timestamp = Sys.timeMillis();
    }

    final public void setDefaultTimeout(int ms) {
        if (ms < 0)
            throw new RuntimeException("Invalid argument, timeout must be >= 0");
        this.DEFAULT_TIMEOUT = ms;
    }

    final public int getDefaultTimeout() {
        return this.DEFAULT_TIMEOUT;
    }

    final public Endpoint remote() {
        return remote != null ? remote : conn.remoteEndpoint();
    }

    @Override
    public boolean expectingReply() {
        return replyHandlerId != 0;
    }

    @Override
    public RpcHandle reply(RpcMessage msg) {
        return reply(msg, null, 0);
    }

    @Override
    public RpcHandle reply(RpcMessage msg, RpcHandler handler) {
        return reply(msg, handler, -1);
    }

    @Override
    public RpcHandle reply(RpcMessage msg, RpcHandler handler, int timeout) {
        Thread.dumpStack();
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends RpcMessage> T request(Endpoint dst, RpcMessage m) {
        RpcHandle reply = send(dst, m, RpcHandler.NONE, Integer.MAX_VALUE);
        // System.err.printf("RTT for: %s  = %s\n", m, reply.rtt());
        if (!reply.failed()) {
            reply = reply.getReply();
            if (reply != null)
                return (T) reply.getPayload();
        }
        return null;
    }

    @Override
    public boolean failed() {
        return failed;
    }

    @Override
    public boolean succeeded() {
        return !failed;
    }

    @Override
    public Endpoint remoteEndpoint() {
        return remote == null ? conn.remoteEndpoint() : remote;
    }

    @Override
    public RpcHandle send(Endpoint dst, RpcMessage m) {
        return send(dst, m, null, 0);
    }

    @Override
    public RpcHandle send(Endpoint dst, RpcMessage m, RpcHandler replyHandler) {
        return send(dst, m, replyHandler, DEFAULT_TIMEOUT);
    }

    @Override
    abstract public RpcHandle send(Endpoint dst, RpcMessage m, RpcHandler replyHandler, int timeout);

    @SuppressWarnings("unchecked")
    @Override
    public <T extends RpcEndpoint> T setHandler(final RpcHandler handler) {
        this.handler = handler;
        return (T) this;
    }

    @Override
    public RpcMessage getPayload() {
        return payload;
    }

    @Override
    public RpcHandle getReply() {
        return reply;
    }

    protected RpcMessage getReplyPayload() {
        return reply != null ? reply.payload : null;
    }

    @Override
    final public void read(Kryo kryo, Input input) {
        this.handlerId = input.readLong();
        this.replyHandlerId = input.readLong();
        this.payload = (RpcMessage) kryo.readClassAndObject(input);
    }

    @Override
    final public void write(Kryo kryo, Output output) {
        output.writeLong(this.handlerId);
        output.writeLong(this.replyHandlerId);
        kryo.writeClassAndObject(output, payload);
    }
}