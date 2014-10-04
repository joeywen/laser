package com.github.ompc.laser.server.datasource;

import java.io.IOException;

/**
 * 数据持久化
 * Created by vlinux on 14-10-4.
 */
public interface DataPersistence {


    /**
     * 保存一行数据
     *
     * @param row
     * @throws IOException
     */
    void putRow(Row row) throws IOException;

    /**
     * 初始化数据持久化
     *
     * @throws IOException 数据持久化初始化失败
     */
    void init() throws IOException;

    /**
     * 完成尚未完成的持久化
     *
     * @throws IOException 完成持久化失败
     */
    void finish() throws IOException;

    /**
     * 销毁数据持久化
     *
     * @throws IOException 数据持久化销毁失败
     */
    void destroy() throws IOException;


}
