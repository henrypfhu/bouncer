package org.javastack.bouncer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Forward Plain Connections
 */
class PlainServer {
	final ServerContext context;
	final InboundAddress inboundAddress;
	final OutboundAddress outboundAddress;

	PlainServer(final ServerContext context, final InboundAddress inboundAddress,
			final OutboundAddress outboundAddress) {
		this.context = context;
		this.inboundAddress = inboundAddress;
		this.outboundAddress = outboundAddress;
	}

	void listenLocal() { // Entry Point
		final PlainListen acceptator = new PlainListen();
		context.addReloadableAwaiter(acceptator);
		context.submitTask(acceptator, "ForwardListen[" + inboundAddress + "|" + outboundAddress + "]",
				ClientId.newId());
	}

	class PlainListen implements Awaiter, Runnable {
		ServerSocket listen;
		volatile boolean shutdown = false;

		@Override
		public void setShutdown() {
			shutdown = true;
			context.closeSilent(listen);
		}

		@Override
		public void run() {
			try {
				inboundAddress.resolve();
				listen = inboundAddress.listen();
				Log.info(this.getClass().getSimpleName() + " started: " + inboundAddress);
				while (!shutdown) {
					try {
						final Socket client = listen.accept();
						context.registerSocket(client);
						final Integer pReadTimeout = inboundAddress.getOpts().getInteger(
								Options.P_READ_TIMEOUT);
						if (pReadTimeout != null) {
							client.setSoTimeout(pReadTimeout);
						}
						Log.info(this.getClass().getSimpleName() + " New client from=" + client);
						context.submitTask(new PlainConnector(client), "ForwardConnect[" + inboundAddress
								+ "|" + outboundAddress + "|" + IOHelper.socketRemoteToString(client) + "]",
								ClientId.newId());
					} catch (IOException e) {
						if (!listen.isClosed()) {
							Log.error(this.getClass().getSimpleName() + " " + e.toString());
						}
					} catch (Exception e) {
						Log.error(this.getClass().getSimpleName() + " Generic exception", e);
					}
				}
			} catch (IOException e) {
				if (!listen.isClosed()) {
					Log.error(this.getClass().getSimpleName() + " " + e.toString());
				}
			} catch (Exception e) {
				Log.error(this.getClass().getSimpleName() + " Generic exception", e);
			} finally {
				Log.info(this.getClass().getSimpleName() + " await end");
				context.awaitShutdown(this);
				Log.info(this.getClass().getSimpleName() + " end");
			}
		}
	}

	class PlainConnector implements Shutdownable, Runnable {
		final Socket client;
		Socket remote = null;
		volatile boolean shutdown = false;

		PlainConnector(final Socket client) {
			this.client = client;
		}

		@Override
		public void setShutdown() {
			shutdown = true;
			close();
		}

		void close() {
			context.closeSilent(client);
			context.closeSilent(remote);
		}

		@Override
		public void run() {
			Log.info(this.getClass().getSimpleName() + " started: " + outboundAddress);
			try {
				outboundAddress.resolve();
				remote = outboundAddress.connect();
				if (remote == null)
					throw new ConnectException("Unable to connect to " + outboundAddress);
				Log.info(this.getClass().getSimpleName() + " Bouncer from " + client + " to " + remote);
				final PlainSocketTransfer st1 = new PlainSocketTransfer(client, remote);
				final PlainSocketTransfer st2 = new PlainSocketTransfer(remote, client);
				st1.setBrother(st2);
				st2.setBrother(st1);
				context.submitTask(
						st1,
						"ForwardTransfer-CliRem[" + inboundAddress + "|"
								+ IOHelper.socketRemoteToString(client) + "|"
								+ IOHelper.socketRemoteToString(remote) + "]", ClientId.getId());
				context.submitTask(
						st2,
						"ForwardTransfer-RemCli[" + inboundAddress + "|"
								+ IOHelper.socketRemoteToString(remote) + "|"
								+ IOHelper.socketRemoteToString(client) + "]", ClientId.getId());
			} catch (IOException e) {
				Log.error(this.getClass().getSimpleName() + " " + e.toString());
				close();
			} catch (Exception e) {
				Log.error(this.getClass().getSimpleName() + " Generic exception", e);
				close();
			} finally {
				Log.info(this.getClass().getSimpleName() + " ended: " + outboundAddress);
			}
		}
	}

	/**
	 * Transfer data between sockets
	 */
	class PlainSocketTransfer implements Shutdownable, Runnable {
		final byte[] buf = new byte[Constants.BUFFER_LEN];
		final Socket sockin;
		final Socket sockout;
		final InputStream is;
		final OutputStream os;
		volatile boolean shutdown = false;

		long keepalive = System.currentTimeMillis();
		PlainSocketTransfer brother = null;

		PlainSocketTransfer(final Socket sockin, final Socket sockout) throws IOException {
			this.sockin = sockin;
			this.sockout = sockout;
			this.is = sockin.getInputStream();
			this.os = sockout.getOutputStream();
		}

		void setBrother(final PlainSocketTransfer brother) {
			this.brother = brother;
		}

		@Override
		public void setShutdown() {
			shutdown = true;
		}

		@Override
		public void run() {
			try {
				while (true) {
					try {
						if (transfer()) {
							keepalive = System.currentTimeMillis();
							continue;
						}
					} catch (SocketTimeoutException e) {
						Log.info(this.getClass().getSimpleName() + " " + e.toString());
						if (brother == null)
							break;
						try {
							// Idle Timeout
							if ((System.currentTimeMillis() - brother.keepalive) > sockin.getSoTimeout()) {
								break;
							}
						} catch (Exception brk) {
							break;
						}
					}
				}
			} catch (IOException e) {
				if (!sockin.isClosed() && !shutdown) {
					Log.error(this.getClass().getSimpleName() + " " + e.toString() + " " + sockin);
				}
			} catch (Exception e) {
				Log.error(this.getClass().getSimpleName() + " Generic exception", e);
			} finally {
				IOHelper.closeSilent(is);
				IOHelper.closeSilent(os);
				context.closeSilent(sockin);
				Log.info(this.getClass().getSimpleName() + " Connection closed " + sockin);
			}
		}

		boolean transfer() throws IOException {
			final int len = is.read(buf, 0, buf.length);
			if (len < 0) {
				context.closeSilent(sockin);
				throw new EOFException("EOF");
			}
			os.write(buf, 0, len);
			os.flush();
			return true;
		}
	}
}