package com.misu.framework.fileClient.impl;

import com.misu.framework.fileClient.FileClientApi;
import com.misu.framework.fileClient.domain.FileInfo;
import com.misu.framework.util.FileUtils;
import com.misu.framework.config.file.FilePathConfig;
import com.misu.framework.config.common.ServerConfig;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地文件工具
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix="fileClient",name = "type", havingValue = "local", matchIfMissing = true)
public class LocalFileClientApiImpl implements FileClientApi {

    @Resource
    private ServerConfig serverConfig;

    @Resource
    private FilePathConfig filePathConfig;

    @Override
    @SneakyThrows
    public String uploadTmpFile(InputStream inputStream) {
        return uploadFile(inputStream, "tmp/" + System.currentTimeMillis() + ".png");
    }

    @Override
    @SneakyThrows
    public String uploadFile(InputStream inputStream, String remotePath) {
        File imageFile =  new File(filePathConfig.getFilePath() + remotePath);
        try (FileOutputStream outputStream = new FileOutputStream(imageFile)) {
            byte[] buf = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buf)) > 0) {
                outputStream.write(buf, 0, bytesRead);
            }
        }

        //返回可访问获取的网络链接
        return "http://" + serverConfig.getIp() + ":" + serverConfig.getPort() + "/img/getImage/" + imageFile.getName();
    }

    @Override
    @SneakyThrows
    public InputStream downloadFile(String remotePath) {
        return new FileInputStream(filePathConfig.getFilePath() + remotePath);
    }

    @Override
    public void deleteFile(String remotePath) {
        FileUtils.deleteFile(filePathConfig.getFilePath() + remotePath);
    }

    @Override
    public void deleteTmpFile() {
        FileUtils.deleteAllFileFromFolder(new File(filePathConfig.getFilePath() + "tmp"));
    }

    @Override
    public List<FileInfo> downloadDirectory(String remotePath) throws FileNotFoundException {
        List<FileInfo> fileInfoList = new ArrayList<>();
        getAllFilesFromDirectory(remotePath, fileInfoList);
        return fileInfoList;
    }

    @Override
    public boolean isDirectory(String remotePath) throws FileNotFoundException {
        File file = new File(filePathConfig.getFilePath() + remotePath);
        if (file.exists()) {
            return file.isDirectory();
        } else {
            throw new FileNotFoundException("文件不存在");
        }
    }

    /**
     * 递归获取目录下所有文件
     */
    private void getAllFilesFromDirectory(String remotePath, List<FileInfo> fileInfoList) throws FileNotFoundException {
        File file = new File(filePathConfig.getFilePath() + remotePath);
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File subFile : files) {
                    if (subFile.isDirectory()) {
                        getAllFilesFromDirectory(remotePath + "/" + subFile.getName(), fileInfoList);
                    } else {
                        FileInfo fileInfo = new FileInfo();
                        fileInfo.setFileName(subFile.getName());
                        fileInfo.setFilePath(remotePath);
                        fileInfo.setInputStream(new FileInputStream(subFile));
                        fileInfoList.add(fileInfo);
                    }
                }
            }
        }
    }
}
