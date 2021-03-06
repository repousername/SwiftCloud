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
package swift.proto;

import java.util.logging.Logger;

import swift.pubsub.BatchUpdatesNotification;
import swift.pubsub.UpdateNotification;
import sys.net.api.rpc.AbstractRpcHandler;
import sys.net.api.rpc.RpcHandle;
import sys.net.api.rpc.RpcMessage;
import sys.pubsub.PubSubNotification;

/**
 * 
 * @author smduarte
 * 
 */
public class SwiftProtocolHandler extends AbstractRpcHandler {
    private static Logger logger = Logger.getLogger(SwiftProtocolHandler.class.getName());

    protected void onReceive(RpcHandle conn, UnsubscribeUpdatesReply request) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, UnsubscribeUpdatesRequest request) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, BatchFetchObjectVersionRequest request) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, BatchFetchObjectVersionReply reply) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, LatestKnownClockRequest request) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, LatestKnownClockReply reply) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, CommitTSRequest request) {
        Thread.dumpStack();
    }

    protected void onReceive(CommitTSReply reply) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, BatchCommitUpdatesRequest request) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, BatchCommitUpdatesReply reply) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, CommitUpdatesRequest request) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, CommitUpdatesReply reply) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, GenerateDCTimestampRequest request) {
        Thread.dumpStack();
    }

    protected void onReceive(GenerateDCTimestampReply reply) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, final SeqCommitUpdatesRequest request) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, final SeqCommitUpdatesReply reply) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, final PingRequest request) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, final PingReply reply) {
        Thread.dumpStack();
    }

    // For the pseudo DHT------------------------------
    protected void onReceive(RpcHandle conn, DHTExecCRDT request) {
        Thread.dumpStack();
    }

    protected void onReceive(DHTExecCRDTReply reply) {
        Thread.dumpStack();
    }

    protected void onReceive(RpcHandle conn, DHTGetCRDT request) {
        Thread.dumpStack();
    }

    protected void onReceive(DHTGetCRDTReply reply) {
        Thread.dumpStack();
    }

    // For PubSub --------------------------------------
    public void onReceive(RpcHandle conn, PubSubHandshake request) {
        Thread.dumpStack();
    }

    // For PubSub --------------------------------------
    public void onReceive(RpcHandle conn, PubSubHandshakeReply reply) {
        Thread.dumpStack();
    }

    public void onReceive(UpdateNotification evt) {
        Thread.dumpStack();
    }

    public void onReceive(BatchUpdatesNotification evt) {
        Thread.dumpStack();
    }

    public void onReceive(PubSubNotification<?> evt) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onReceive(RpcMessage m) {
        logger.warning("unhandled RPC message " + m);
        Thread.dumpStack();
    }

    @Override
    public void onReceive(RpcHandle handle, RpcMessage m) {
        Thread.dumpStack();
        logger.warning("unhandled RPC message " + m);
    }

}
