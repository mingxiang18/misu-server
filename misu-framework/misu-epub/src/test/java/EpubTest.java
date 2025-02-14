import nl.siegmann.epublib.domain.*;
import nl.siegmann.epublib.epub.EpubReader;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.*;
import java.util.*;

public class EpubTest {

    private static List<String> pages = new ArrayList<>();
    private static int currentPage = 0;
    private static final int MAX_CHAR_PER_LINE = 70; // 每行最多显示20个字符
    private static final String PAGE_FILE = "page.txt"; // 存储当前页数的文件

    public static void main(String[] args) {
        File file = new File("D:\\develop\\bot\\misu-server\\user-file\\public\\新建文件夹\\guimi.epub");
        InputStream in = null;
        try {
            // 读取上次保存的页数
            readSavedPage();

            // 从输入流当中读取epub格式文件
            EpubReader reader = new EpubReader();
            in = new FileInputStream(file);
            Book book = reader.readEpub(in);

            // 获取书本的头部信息
            Metadata metadata = book.getMetadata();
            System.out.println("书名：" + metadata.getFirstTitle());
            System.out.println("作者：" + metadata.getAuthors());

            // 获取书本的内容资源
            List<Resource> contents = book.getContents();
            System.out.println("内容资源数量为：" + contents.size());

            // 获取到书本的spine资源 线性排序
            Spine spine = book.getSpine();
            System.out.println("spine资源数量为：" + spine.size());

            // 提取每个章节的内容并分成分页显示
            for (SpineReference spineReference : spine.getSpineReferences()) {
                Resource resource = spineReference.getResource();
                byte[] data = resource.getData();

                // 使用 JSoup 解析 HTML 内容
                String content = new String(data, "UTF-8");
                Document doc = Jsoup.parse(content);

                // 提取章节标题（假设标题在 <h1>, <h2> 等标签中）
                String title = extractTitle(doc);

                // 提取正文内容，去掉 HTML 标签
                String textContent = extractText(doc);

                // 仅显示标题和正文内容
                if (!title.isEmpty()) {
                    pages.add("章节标题: " + title);
                }
                if (!textContent.isEmpty()) {
                    // 将正文内容分割成符合每行20个字符的分页内容，同时处理换行符
                    pages.addAll(splitTextIntoPages(textContent));
                }
            }

            // 启动阅读功能
            startReading();

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 一定要关闭资源
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // 提取章节标题
    private static String extractTitle(Document doc) {
        String title = "";
        // 尝试提取 <h1>, <h2> 或类似的标题标签
        Element titleElement = doc.select("h1, h2, h3").first();
        if (titleElement != null) {
            title = titleElement.text();
        }
        return title;
    }

    // 提取正文内容，去掉 HTML 标签
    private static String extractText(Document doc) {
        // 保留 <p> 和 <br> 标签
        doc.select("br").prepend("\n"); // 处理 <br> 标签为换行
        doc.select("p").prepend("\n");  // 处理 <p> 标签为换行

        // 获取文本内容，保留换行
        String text = doc.html().replaceAll("<[^>]*>", "").trim();

        // 将连续的换行符替换为一个换行符，并删除空行
        text = text.replaceAll("\n+", "\n"); // 将多个换行符替换为一个
        text = text.replaceAll("(?m)^[\\s]*\n", ""); // 删除空行

        return text;
    }

    // 将文本内容分割成符合每行最大字符数的分页内容
    private static List<String> splitTextIntoPages(String text) {
        List<String> lines = new ArrayList<>();
        String[] paragraphs = text.split("\n"); // 按换行符分割段落

        // 遍历每段文本
        for (String paragraph : paragraphs) {
            int length = paragraph.length();

            // 按照最大字符数每次切割一行
            for (int i = 0; i < length; i += MAX_CHAR_PER_LINE) {
                int end = Math.min(i + MAX_CHAR_PER_LINE, length);
                lines.add(paragraph.substring(i, end));
            }
        }

        return lines;
    }

    // 启动阅读功能
    private static void startReading() {
        Scanner scanner = new Scanner(System.in);
        boolean reading = true;

        System.out.println("\n按a/s翻页，按 q 退出");

        while (reading) {
            // 每次显示两行
            if (currentPage < pages.size()) {
                System.out.println(pages.get(currentPage));
                if (currentPage + 1 < pages.size()) {
                    System.out.println(pages.get(currentPage + 1));
                }
            } else {
                System.out.println("已经到达书本结尾！");
                break;
            }

            // 等待用户输入
            String input = scanner.nextLine();

            // 控制翻页和退出
            if (input.equals("q") || input.equals("'") || input.equals("‘") || input.equals("’")) {
                System.out.println("保存页数中");
                // 退出时保存当前页数
                saveCurrentPage();
                System.out.println("------------------");
                System.out.println("------------------");
                System.out.println("------------------");
                System.out.println("------------------");
                System.out.println("------------------");
                System.out.println("------------------");
                System.out.println("------------------");
                System.out.println("------------------");
                System.out.println("------------------");
                reading = false;  // 退出程序
            } else if (input.equals("s") || StringUtils.isBlank(input) || input.equals("]")) {
                if (currentPage + 2 < pages.size()) {
                    currentPage += 2;  // 下一页
                } else {
                    System.out.println("没有更多的内容了！");
                    reading = false;
                }
            } else if (input.equals("a") || input.equals("[")) {
                if (currentPage - 2 >= 0) {
                    currentPage -= 2;  // 上一页
                } else {
                    System.out.println("已经是第一页了！");
                }
            }
        }

        scanner.close();
    }

    // 读取上次保存的页数
    private static void readSavedPage() {
        try {
            File file = new File(PAGE_FILE);
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String savedPage = reader.readLine();
                if (savedPage != null) {
                    currentPage = Integer.parseInt(savedPage);
                }
                reader.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 保存当前页数
    private static void saveCurrentPage() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(PAGE_FILE));
            writer.write(String.valueOf(currentPage));
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
