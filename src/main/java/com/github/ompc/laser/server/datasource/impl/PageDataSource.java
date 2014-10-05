package com.github.ompc.laser.server.datasource.impl;

import com.github.ompc.laser.server.datasource.DataSource;
import com.github.ompc.laser.server.datasource.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static com.github.ompc.laser.common.LaserUtils.unmap;
import static java.lang.Thread.currentThread;
import static java.nio.channels.FileChannel.MapMode.READ_ONLY;

/**
 * 分页数据源
 * Created by vlinux on 14-10-5.
 */
public class PageDataSource implements DataSource {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final File dataFile;

    /*
     * 空数据
     */
    private final static byte[] EMPTY_DATA = new byte[0];

    /*
     * 缓存页大小,要求是4K倍数
     */
    private final static int BUFFER_SIZE = 256 * 1024 * 1024;

    /*
     * 页行大小<br/>
     * 一行数据构成：有效字节数(4B)+数据段(210B)+填充段(42B) = 256B
     */
    private final int PAGE_ROW_SIZE = 256;

    /*
     * 页行数<br/>
     * 一页中总共有几行
     */
    private final int PAGE_ROWS_NUM = 1000000;

    /*
     * 页码表大小<br/>
     * 一共有几页
     */
    private final int PAGE_TABLE_SIZE = 12;

    /*
     * 页码表
     */
    private Page[] pageTable = new Page[PAGE_TABLE_SIZE];

    /*
     * 行计数器
     */
    private final AtomicInteger lineCounter = new AtomicInteger(0);

    /*
     * 标记是否曾经有其他线程到达过EOF状态
     */
    private volatile boolean isEOF = false;

    /*
     * 页面切换者锁
     */
    private final ReentrantLock pageSwitchLock = new ReentrantLock();
    private final Condition pageSwitchWakeupCondition = pageSwitchLock.newCondition();


    public PageDataSource(File dataFile) {
        this.dataFile = dataFile;
    }

    @Override
    public Row getRow() throws IOException {

        // 先找到当前页,并判断当前页是否已完结
        // 如果当前页是最后一页,则直接返回EOF
        // 如果当前页不是最后一页,则通知切换者

        final int lastLineNum = lineCounter.get();
        final int lastPageNum = lastLineNum / PAGE_ROWS_NUM;
        final int lastTableIdx = lastPageNum % PAGE_TABLE_SIZE;
        final Page lastPage = pageTable[lastTableIdx];
        if (lastPage.isEmpty()) {

            if (lastPage.isLast) {
                while(!isEOF) {

                }
                return new Row(-1, EMPTY_DATA);
            } else {
                pageSwitchLock.lock();
                try {
                    pageSwitchWakeupCondition.signal();
                } finally {
                    pageSwitchLock.unlock();
                }
            }

        }

        // line ++
        // read --

        final int lineNum = lineCounter.getAndIncrement();
        final int pageNum = lineNum / PAGE_ROWS_NUM;
        final int tableIdx = pageNum % PAGE_TABLE_SIZE;
        while (pageTable[tableIdx].isLocked
                && pageTable[tableIdx].pageNum != pageNum) {
            // TODO : 优化自旋锁
            Thread.yield();
            // 如果页码表中当前位置所存放的页面编码对应不上
            // 则认为页切换不及时，这里采用自旋等待策略，其实相当危险
            log.info("debug for spin, page.pageNum={},pageNum={},lineNum={}",
                    new Object[]{pageTable[tableIdx].pageNum, pageNum, lineNum});
        }

        final Page page = pageTable[tableIdx];

        int dec = page.readCount.decrementAndGet();
        if ( dec < 0 ) {
            log.info("debug for 0, page.pageNum={},pageNum={},lineNum={},dec={}",
                    new Object[]{pageTable[tableIdx].pageNum, pageNum, lineNum,dec});
            return new Row(-1, EMPTY_DATA);
        }

        final int rowNum = lineNum % PAGE_ROWS_NUM;
        final int offsetOfRow = rowNum * PAGE_ROW_SIZE;

        final ByteBuffer byteBuffer = ByteBuffer.wrap(page.data, offsetOfRow, PAGE_ROW_SIZE);
        final int validByteCount = byteBuffer.getInt();
        final byte[] data = new byte[validByteCount];
        byteBuffer.get(data);


        if( page.isLast
                && page.isEmpty()) {
            isEOF = true;
        }

        return new Row(lineNum, data);


    }

    @Override
    public void init() throws IOException {

        // 初始化页码表
        for (int i = 0; i < pageTable.length; i++) {
            final Page page = new Page();
            page.pageNum = i;
            pageTable[i] = page;
        }

        // 并发将文件映射到内存中



        /*
         * 页面切换者<br/>
         * 切换页码表中已完成的页面
         */
        final Thread pageSwitcher = new Thread(() -> {

            // 下一次要替换掉的页码表编号(0~PAGE_TABLE_SIZE)
            int nextSwitchPageTableIndex = 0;

            // 文件读取偏移量
            long fileOffset = 0;

            try (final FileChannel fileChannel = new RandomAccessFile(dataFile, "r").getChannel()) {

                // 文件整体大小
                final long fileSize = fileChannel.size();

                // 文件缓存
                MappedByteBuffer mappedBuffer = null;

                // 行解析状态机
                DecodeLineState state = DecodeLineState.READ_D;

                MAIN_LOOP:
                while (fileOffset < fileSize) {

                    // 遍历页码表，主要做两件事
                    // 1.顺序的更换页码
                    // 2.将文件缓存刷入页码
                    final Page page = pageTable[nextSwitchPageTableIndex];

                    if (page.isInit
                            && !page.isEmpty()) {
                        // 如果已经被初始化后的当前页还没被读完,休眠等待被唤醒
                        pageSwitchLock.lock();
                        try {
                            // 休眠100ms,或被唤醒
                            pageSwitchWakeupCondition.await(100, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            currentThread().interrupt();
                        } finally {
                            pageSwitchLock.unlock();
                        }//try
                    }

                    if (!page.isInit
                            || page.isEmpty()) {

                        final ByteBuffer dataBuffer = ByteBuffer.wrap(page.data);

                        // 页面中的行号
                        int rowIdx = 0;

                        final ByteBuffer tempBuffer = ByteBuffer.allocate(PAGE_ROW_SIZE);

                        FILL_PAGE_LOOP:
                        while (true) {
                            // 只有页面尚未被填满的时候才需要开始填充

                            if (null == mappedBuffer
                                    || !mappedBuffer.hasRemaining()) {
                                // 如果文件缓存是第一次加载,或者已到达尽头,需要做一次切换映射
                                // 修正映射长度
                                final long fixLength = (fileOffset + BUFFER_SIZE >= fileSize) ? fileSize - fileOffset : BUFFER_SIZE;

                                if (null != mappedBuffer) {
                                    unmap(mappedBuffer);
                                }

                                if (fixLength > 0) {
                                    mappedBuffer = fileChannel.map(READ_ONLY, fileOffset, fixLength);
                                }
                            }

                            if (!mappedBuffer.hasRemaining()) {
                                // 如果刚切完的文件缓存就已经没数据了,说明到达了EOF
                                // 需要关闭页面切换者
                                // 将当前页标记为最后一页
                                page.isLast = true;
                                break FILL_PAGE_LOOP;
                            }

                            DECODE_LOOP:
                            while (mappedBuffer.hasRemaining()) {
                                switch (state) {
                                    case READ_D: {
                                        final byte b = mappedBuffer.get();
                                        fileOffset++;
                                        if (b == '\r') {
                                            state = DecodeLineState.READ_R;
                                        } else {
                                            tempBuffer.put(b);
                                            break;
                                        }
                                    }

                                    case READ_R: {
                                        final byte b = mappedBuffer.get();
                                        fileOffset++;
                                        if (b != '\n') {
                                            throw new IOException("illegal format, \\n did not behind \\r, b=" + b);
                                        }
                                        state = DecodeLineState.READ_N;
                                    }

                                    case READ_N: {
                                        state = DecodeLineState.READ_D;

                                        // 将临时缓存中的数据填入页中
                                        tempBuffer.flip();
                                        final int dataLength = tempBuffer.limit();
                                        dataBuffer.putInt(dataLength);
                                        dataBuffer.put(tempBuffer);
                                        tempBuffer.clear();


                                        // 重新计算当前行偏移量
                                        if (++rowIdx == PAGE_ROWS_NUM) {
                                            // 一页已经被填满,跳出本次页面填充动作
                                            break FILL_PAGE_LOOP;
                                        }

                                        int offsetOfRow = rowIdx * PAGE_ROW_SIZE;
                                        dataBuffer.position(offsetOfRow);

                                        break;
                                    }

                                    default:
                                        throw new IOException("init failed, illegal state=" + state);
                                }//switch:state

                            }//while:MAPPED

                        }//while:FILL_PAGE_LOOP

                        // 重新计算页面参数
                        page.rowCount = rowIdx;
                        page.readCount.set(rowIdx);
                        log.info("page.pageNum={} was switched. fileOffset={},fileSize={},page.rowCount={};",
                                new Object[]{page.pageNum, fileOffset, fileSize, page.rowCount});

                        if (fileOffset == fileSize) {
                            page.isLast = true;
                            log.info("page.pageNum={} is last, page.readCount={}", page.pageNum, page.readCount.get());
                        }

                        if (page.isInit) {
                            // 对初始化的页面不需要累加页面编号
                            page.pageNum += PAGE_TABLE_SIZE;
                        } else {
                            page.isInit = true;
                        }

                        page.isLocked = false;


                        // 最后一步，别忘记更新下一次要替换的页码表号
                        nextSwitchPageTableIndex = (nextSwitchPageTableIndex + 1) % PAGE_TABLE_SIZE;

                    }

                }//while

            } catch (IOException ioe) {
                log.warn("mapping file={} failed.", dataFile, ioe);
            }

            log.info("PageDataSource(file:{}) was arrive EOF.", dataFile);

        }, "PageDataSource-PAGESWITCHER-daemon");
        pageSwitcher.setDaemon(true);
        pageSwitcher.start();

        log.info("PageDataSource(file:{}) was inited.", dataFile);

    }

    @Override
    public void destroy() throws IOException {
        log.info("PageDataSource(file:{}) was destroyed.", dataFile);
    }


    /**
     * 缓存页
     */
    class Page {

        /*
         * 页码
         */
        int pageNum;

        /*
         * 页面总行数
         */
        int rowCount = 0;

        /*
         * 已被读取行数
         */
        AtomicInteger readCount = new AtomicInteger(0);

        /*
         * 是否最后一页
         */
        volatile boolean isLast = false;

        /*
         * 当前页面是否已经被初始化<br/>
         * 因为第一次读取数据的时候需要页面切换者进行读取,可以说是预加载
         */
        volatile boolean isInit = false;

        /*
         * 标记当前页是否被锁定,被锁定的页面只允许页面切换者切换完成后才能读取
         */
        volatile boolean isLocked = false;

        /*
         * 数据段
         */
        byte[] data = new byte[PAGE_ROW_SIZE * PAGE_ROWS_NUM];

        /**
         * 判断当前页面是否已经被读完
         *
         * @return
         */
        boolean isEmpty() {
            return readCount.get() <= 0;
        }

    }

    /**
     * 行解析状态机
     */
    private enum DecodeLineState {
        READ_D, // 读取数据
        READ_R, // 读取\r
        READ_N, // 读取\n
    }

}
