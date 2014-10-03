package com.github.ompc.laser.client;

import com.github.ompc.laser.common.LaserConstant;
import com.github.ompc.laser.common.LaserOptions;
import com.github.ompc.laser.common.networking.GetDataReq;
import com.github.ompc.laser.common.networking.GetDataResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import static com.github.ompc.laser.common.LaserConstant.PRO_RESP_GETDATA;
import static com.github.ompc.laser.common.LaserConstant.PRO_RESP_GETEOF;
import static com.github.ompc.laser.common.SocketUtils.format;
import static java.lang.Thread.currentThread;
import static java.nio.channels.SelectionKey.OP_CONNECT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

/**
 * NIO版本的LaserClient
 * Created by vlinux on 14-10-3.
 */
public class NioLaserClient {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final CountDownLatch countDown;
    private final ExecutorService executorService;
    private final ClientConfiger configer;
    private final LaserOptions options;

    private SocketChannel socketChannel;
    private volatile boolean isRunning = true;


    public NioLaserClient(CountDownLatch countDown, ExecutorService executorService, ClientConfiger configer, LaserOptions options) {
        this.countDown = countDown;
        this.executorService = executorService;
        this.configer = configer;
        this.options = options;
    }

    /**
     * 获取并配置SocketChannel
     *
     * @return
     * @throws IOException
     */
    private SocketChannel getAndConfigSocketChannel() throws IOException {
        final SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        // config the socket
        final Socket socket = socketChannel.socket();
        socket.setTcpNoDelay(options.isClientTcpNoDelay());
        socket.setReceiveBufferSize(options.getClientReceiverBufferSize());
        socket.setSendBufferSize(options.getClientSendBufferSize());
        socket.setSoTimeout(options.getClientSocketTimeout());
        socket.setPerformancePreferences(
                options.getClientPerformancePreferences()[0],
                options.getClientPerformancePreferences()[1],
                options.getClientPerformancePreferences()[2]);
        socket.setTrafficClass(options.getClientTrafficClass());
        return socketChannel;
    }

    /**
     * 链接到网络
     *
     * @throws java.io.IOException
     */
    public void connect() throws IOException {
        socketChannel = getAndConfigSocketChannel();

        socketChannel.connect(configer.getServerAddress());
        // waiting for connect
        try (final Selector selector = Selector.open()) {
            socketChannel.register(selector, OP_CONNECT);
            WAITING_FOR_CONNECT:
            for (; ; ) {
                selector.select();
                final Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    final SelectionKey key = iter.next();
                    iter.remove();

                    if (key.isConnectable()) {
                        final SocketChannel channel = (SocketChannel) key.channel();
                        if (channel.isConnectionPending()) {
                            // block until connect finished
                            channel.finishConnect();
                            break WAITING_FOR_CONNECT;
                        }
                    }//if

                }//while
            }//for

        }//try

        log.info("{} connect successed.", format(socketChannel.socket()));

    }

    /**
     * GetDataResp 处理器
     */
    private class GetDataRespHandler implements Runnable {

        private final GetDataResp resp;

        private GetDataRespHandler(GetDataResp resp) {
            this.resp = resp;
            currentThread().setName("client-" + format(socketChannel.socket()) + "-worker");
        }

        @Override
        public void run() {

            // TODO : put to file
            log.info("{} receive data, lineNum={},len={}",
                    new Object[]{format(socketChannel.socket()), resp.getLineNum(), resp.getData().length});

        }

    }

    /**
     * 读线程
     */
    final Runnable writer = new Runnable() {

        @Override
        public void run() {
            currentThread().setName("client-" + format(socketChannel.socket()) + "-writer");
            try (final Selector selector = Selector.open()) {

                final ByteBuffer buffer = ByteBuffer.allocate(options.getClientSendBufferSize());
                while (isRunning) {

                    final GetDataReq req = new GetDataReq();
                    if (buffer.remaining() >= Integer.BYTES) {
                        buffer.putInt(req.getType());
                        continue;
                    } else {
                        socketChannel.register(selector, OP_WRITE);
                        buffer.flip();
                    }

                    selector.select();
                    final Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                    while (iter.hasNext()) {
                        final SelectionKey key = iter.next();
                        iter.remove();

                        if (key.isWritable()) {
                            final int count = socketChannel.write(buffer);
//                            log.info("debug for write, count="+count);
                            buffer.compact();
                            key.interestOps(key.interestOps() & ~OP_WRITE);
                        }

                    }

                }

            } catch (IOException ioe) {
                if (!socketChannel.socket().isClosed()) {
                    log.warn("{} write failed.", format(socketChannel.socket()), ioe);
                }
            }
        }

    };

    private enum DecodeState {
        READ_TYPE,
        READ_GETDATA_LINENUM,
        READ_GETDATA_LEN,
        READ_GETDATA_DATA,
        READ_GETEOF
    }

    /**
     * 写线程
     */
    final Runnable reader = new Runnable() {

        @Override
        public void run() {

            currentThread().setName("client-" + format(socketChannel.socket()) + "-reader");
            final ByteBuffer buffer = ByteBuffer.allocate(options.getClientReceiverBufferSize());
            try (final Selector selector = Selector.open()) {

                // decode
                int type;
                int lineNum = 0;
                int len = 0;
                DecodeState state = DecodeState.READ_TYPE;

                socketChannel.register(selector, OP_READ);
                MAIN_LOOP:
                while(isRunning) {

                    selector.select();
                    final Iterator<SelectionKey> iter = selector.selectedKeys().iterator();

                    while (iter.hasNext()) {
                        final SelectionKey key = iter.next();
                        iter.remove();

                        if (key.isReadable()) {

                            switch( state ) {
                                case READ_TYPE:
                                    socketChannel.read(buffer);
                                    buffer.flip();
                                    if( buffer.limit() < Integer.BYTES ) {
                                        break;
                                    }
                                    type = buffer.getInt();
                                    if( type == PRO_RESP_GETDATA) {
                                        state = DecodeState.READ_GETDATA_LINENUM;
                                    } else if( type == PRO_RESP_GETEOF) {
                                        state = DecodeState.READ_GETEOF;
                                        break;
                                    } else {
                                        throw new IOException("decode failed, illegal type="+type);
                                    }
                                case READ_GETDATA_LINENUM:
                                    if( buffer.limit() < Integer.BYTES ) {
                                        break;
                                    }
                                    lineNum = buffer.getInt();
                                    state = DecodeState.READ_GETDATA_LEN;
                                case READ_GETDATA_LEN:
                                    if( buffer.limit() < Integer.BYTES ) {
                                        break;
                                    }
                                    len = buffer.getInt();
                                    state = DecodeState.READ_GETDATA_DATA;
                                case READ_GETDATA_DATA:
                                    if( buffer.limit() < len ) {
                                        break;
                                    }
                                    final byte[] data = new byte[len];
                                    buffer.get(data);

                                    state = DecodeState.READ_TYPE;

                                    // handler GetDataResp
                                    executorService.execute(new GetDataRespHandler(new GetDataResp(lineNum, data)));

                                    break;
                                case READ_GETEOF:
                                    // 收到EOF，结束整个reader
                                    countDown.countDown();
                                    log.info("{} receive EOF.", format(socketChannel.socket()));
                                    break MAIN_LOOP;

                                default:
                                    throw new IOException("decode failed, illegal state="+state);
                            }//switch

                            buffer.compact();

                        }//if

                    }//while

                }//while:MAIN_LOOP

            } catch (IOException ioe) {
                if (!socketChannel.socket().isClosed()) {
                    log.warn("{} read failed.", format(socketChannel.socket()), ioe);
                }
            }

        }

    };

    /**
     * 开始干活
     *
     * @throws IOException
     */
    public void work() throws IOException {
        executorService.execute(writer);
        executorService.execute(reader);
    }

    /**
     * 断开网络链接
     *
     * @throws IOException
     */
    public void disconnect() throws IOException {

        isRunning = false;
        if( null != socketChannel ) {
            socketChannel.close();
        }

        log.info("{} disconnect successed.", format(socketChannel.socket()));

    }

}
