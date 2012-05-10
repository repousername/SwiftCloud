package sys.net.impl.rpc;

import static sys.Sys.Sys;
import static sys.utils.Log.Log;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.esotericsoftware.kryo.serialize.SimpleSerializer;

import sys.dht.catadupa.RandomList;
import sys.net.api.Endpoint;
import sys.net.api.Message;
import sys.net.api.MessageHandler;
import sys.net.api.NetworkingException;
import sys.net.api.TransportConnection;
import sys.net.api.rpc.RpcEndpoint;
import sys.net.api.rpc.RpcFactory;
import sys.net.api.rpc.RpcHandle;
import sys.net.api.rpc.RpcHandler;
import sys.net.api.rpc.RpcMessage;
import sys.net.impl.FailedTransportConnection;
import sys.net.impl.KryoLib;
import sys.utils.LongMap;
import sys.utils.Threading;

import static sys.net.impl.KryoLib.*;

final public class RpcFactoryImpl implements RpcFactory, MessageHandler {
	static final long MAX_SERVICE_ID = 1L << 16;

	Endpoint local;
	ConnectionManager conMgr;
	List<RpcEndpoint> services = new ArrayList<RpcEndpoint>();

	public RpcFactoryImpl() {
		this.conMgr = new ConnectionManager();

		KryoLib.register(RpcPacket.class, new SimpleSerializer<RpcPacket>() {

			public RpcPacket read(ByteBuffer bb) {
				RpcPacket res = new RpcPacket();
				res.handlerId = bb.getLong();
				res.replyHandlerId = bb.getLong();
				res.payload = (RpcMessage) kryo.readClassAndObject(bb);
				return res;
			}

			@Override
			public void write(ByteBuffer bb, RpcPacket pkt) {
				bb.putLong(pkt.handlerId);
				bb.putLong(pkt.replyHandlerId);
				kryo.writeClassAndObject(bb, pkt.payload);
			}
		});

		gcStaleHandlers();
	}

	public void setEndpoint(Endpoint local) {
		this.local = local;
		this.local.setHandler(this);
	}

	@Override
	public RpcEndpoint rpcService(final int service, final RpcHandler handler) {
		RpcEndpoint res = new RpcPacket(service, handler);
		services.add(res);
		return res;
	}

	@Override
	public RpcEndpoint rpcServiceConnect(int service) {
		return null;
	}

	@Override
	public void onAccept(TransportConnection conn) {
		conMgr.add(conn);
		System.out.println("Accepted connection from:" + conn.remoteEndpoint());
	}

	@Override
	public void onConnect(TransportConnection conn) {
		conMgr.add(conn);
	}

	@Override
	public void onFailure(TransportConnection conn) {
		conMgr.remove(conn);
	}

	public void onReceive(final TransportConnection conn, final RpcPacket pkt) {
		// System.out.println(conn + " " + pkt.payload.getClass());

		final RpcPacket handle = getHandle(pkt);
		if (handle != null) {
			if (handle.streamingIsEnabled )
				handle.reRegisterHandler();

			pkt.conn = conn;
			handle.accept(pkt);
		} else {
			System.err.println("No handler for:" + pkt.payload.getClass() + " " + pkt.handlerId);
			Log.finest("No handler for:" + pkt.payload.getClass() + " " + pkt.handlerId);
		}
	}

	@Override
	public void onReceive(TransportConnection conn, Message m) {
		throw new NetworkingException("Incoming object is not an RpcPacket???");
	}

	@Override
	public void onFailure(Endpoint dst, Message m) {
		Thread.dumpStack();
	}

	final class ConnectionManager {
		final int CONNECTION_RETRIES = 3;
		final int CONNECTION_REPLY_DELAY = 5;
		Map<Endpoint, RandomList<TransportConnection>> connections = new HashMap<Endpoint, RandomList<TransportConnection>>();

		synchronized TransportConnection get(Endpoint remote) {
			RandomList<TransportConnection> rl = connections(remote, true);
			for (TransportConnection i : rl)
				if (!i.failed())
					return i;

			for (int j = 0; j < CONNECTION_RETRIES; j++) {
				TransportConnection res = local.connect(remote);
				if (res != null && !res.failed())
					return res;
			}
			return new FailedTransportConnection(local, remote);
		}

		synchronized void add(TransportConnection conn) {
			if (!conn.failed())
				connections(conn.remoteEndpoint(), true).add(conn);
		}

		synchronized void remove(TransportConnection conn) {
			if (!conn.failed())
				connections(conn.remoteEndpoint(), false).remove(conn);
		}

		RandomList<TransportConnection> connections(Endpoint remote, boolean create) {
			RandomList<TransportConnection> res = connections.get(remote);
			if (res == null && create)
				connections.put(remote, res = new RandomList<TransportConnection>());
			return res;
		}
	}

	final public class RpcPacket extends AbstractRpcPacket {

		boolean isWaiting4Reply = false;

		RpcPacket() {
		}

		RpcPacket(long service, RpcHandler handler) {
			this.timeout = -1;
			this.handler = handler;
			this.handlerId = service;
			this.replyHandlerId = service;
			handles.put(this.handlerId, new StaleRef(this));
		}

		private RpcPacket(Endpoint remote, RpcMessage payload, RpcPacket handle, RpcHandler replyhandler, int timeout) {
			this.remote = remote;
			this.timeout = timeout;
			this.payload = payload;
			this.handler = replyhandler;
			this.handlerId = handle.replyHandlerId;
			if (replyhandler != null) {
				synchronized (handles) {
					this.timestamp = Sys.timeMillis();
					this.replyHandlerId = g_handlers++;
					handles.put(this.replyHandlerId, new StaleRef(this));
				}
			} else
				this.replyHandlerId = 0L;
		}

		@Override
		public Endpoint localEndpoint() {
			return local;
		}

		@Override
		public RpcHandle send(Endpoint remote, RpcMessage msg, RpcHandler replyHandler, int timeout) {
			RpcPacket pkt = new RpcPacket(remote, msg, this, replyHandler, timeout);

			if (timeout != 0)
				synchronized (pkt) {
					// System.out.println("sync for:" + pkt.hashCode() );
					pkt.isWaiting4Reply = true;
					if (pkt.sendRpcSuccess(null, this))
						pkt.waitForReply();
				}
			else {
				pkt.remote = remote;
				pkt.sendRpcSuccess(null, this);
			}
			return pkt;
		}

		public RpcHandle reply(RpcMessage msg, RpcHandler replyHandler, int timeout) {
			RpcPacket pkt = new RpcPacket(remote, msg, this, replyHandler, timeout);

			if (timeout != 0)
				synchronized (pkt) {
					// System.out.println("sync for:" + pkt.hashCode() );
					pkt.isWaiting4Reply = true;
					if (pkt.sendRpcSuccess(conn, this))
						pkt.waitForReply();
				}
			else
				pkt.sendRpcSuccess(conn, this);
			return pkt;
		}

		final void accept(RpcPacket pkt) {
			if (isWaiting4Reply) {
				synchronized (this) {
					reply = pkt;
					Threading.notifyOn(this);
				}
			} else
				pkt.payload.deliverTo(pkt, this.handler);
		}

		final private void waitForReply() {
			while (reply == null && !timedOut())
				;
			
			isWaiting4Reply = false;
			if (reply != null) 
				reply.payload.deliverTo(reply, this.handler);
		}

		final private boolean timedOut() {
			int ms = timeout < 0 ? Integer.MAX_VALUE: (int) (timeout - (Sys.timeMillis() - timestamp));
			if (ms > 0)
				Threading.waitOn(this, ms > 100 ? 100 : ms );
			return ms <= 0;
		}

		final private boolean sendRpcSuccess(TransportConnection conn, AbstractRpcPacket handle) {
			try {
				if (conn != null && conn.send(this) || conMgr.get(remote).send(this)) {
					payload = null;
					return true;
				} else {
					synchronized (handles) {
						handles.remove(this.replyHandlerId);
					}
					handle.handler.onFailure(this);
					return false;
				}
			} catch (Throwable t) {
				failed = true;
				failureCause = t;
				
				if( handler != null )
					handler.onFailure(this);
				else
					handle.handler.onFailure(this);

				return false;
			}
		}

		public String toString() {
			return String.format("RPC(%s,%s,%s)", handlerId, replyHandlerId, this.handler);
		}

		@Override
		public void deliverTo(TransportConnection conn, MessageHandler handler) {
			((RpcFactoryImpl) handler).onReceive(conn, this);
		}

		void reRegisterHandler() {
			synchronized (handles) {
				handles.put(this.replyHandlerId, new StaleRef(this));
			}
		}
	}

	RpcPacket getHandle(RpcPacket other) {
		synchronized (handles) {
			SoftReference<RpcPacket> ref;

			if (other.handlerId < MAX_SERVICE_ID)
				ref = handles.get(other.handlerId);
			else
				ref = handles.remove(other.handlerId);

			return ref == null ? null : ref.get();
		}
	}

	void gcStaleHandlers() {
		Threading.newThread(true, new Runnable() {

			@Override
			public void run() {
				for (;;) {
					try {
						StaleRef ref = (StaleRef) refQueue.remove();
						synchronized (handles) {
							handles.remove(ref.key);
							while ((ref = (StaleRef) refQueue.poll()) != null)
								handles.remove(ref.key);
						}
					} catch (Throwable t) {
						t.printStackTrace();
					}
				}
			}

		}).start();
	}

	final class StaleRef extends SoftReference<RpcPacket> {

		final long key;

		public StaleRef(RpcPacket referent) {
			super(referent, refQueue);
			this.key = referent.replyHandlerId;
		}

	}

	long g_handlers = MAX_SERVICE_ID + Sys.rg.nextInt(100000);

	final LongMap<StaleRef> handles = new LongMap<StaleRef>();
	final ReferenceQueue<RpcPacket> refQueue = new ReferenceQueue<RpcPacket>();
}
