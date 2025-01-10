package com.misu.fileServer.util;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import java.net.URL;
import java.util.List;

public class RssFeedUtils {
    public static void main(String[] args) {
        try {
            // 指定RSS Feed URL
            String feedUrl = "https://anime.b168.net/topics/rss/rss.xml?keyword=%E5%AE%9D%E5%8F%AF%E6%A2%A6+%E5%9C%B0%E5%B9%B3%E7%BA%BF+%E7%AE%80%E4%BD%93&order=date-desc&sort_id=0&team_id=630"; // 替换为实际的RSS源链接

            // 解析RSS源
            URL url = new URL(feedUrl);
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(url));

            // 获取并打印所有条目
            List<SyndEntry> entries = feed.getEntries();
            for (SyndEntry entry : entries) {
                System.out.println("标题: " + entry.getTitle());
                System.out.println("链接: " + entry.getLink());
//                System.out.println("描述: " + entry.getDescription().getValue());
                System.out.println("发布日期: " + entry.getPublishedDate());
                System.out.println("--------");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
