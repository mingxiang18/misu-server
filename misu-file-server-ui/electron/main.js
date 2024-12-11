// 导入模块
const { app, BrowserWindow, screen } = require("electron");
const path = require("path");

const NODE_ENV = "development"; //开发环境;//process.env.NODE_ENV;
// const NODE_ENV = "pro"; //开发环境;//process.env.NODE_ENV;

// 创建主窗口
const createWindow = () => {
    const mainWindow = new BrowserWindow({
        center: true, // 窗口居中显示
        // transparent: true, // 设置窗口透明
        frame: true, //是否显示顶部导航，去掉关闭按钮 最大化最小化按钮
        // titleBarStyle: "hidden", //Windows下创建的窗口是否带边框

        // frame: false, // 隐藏默认标题栏和边框
        titleBarStyle: "hiddenInset", // MacOS下使用内嵌式无边框样式

        backgroundColor: "#02243B", //窗口背景
        // fullscreen: true, //全屏设置
        webPreferences: {
            preload: path.join(__dirname, "preload.js"),
            nodeIntegration: true, //开启true这一步很重要,目的是为了vue文件中可以引入node和electron相关的API
            contextIsolation: true, // 可以使用require方法 // 是否在独立 JavaScript 环境中运行
            enableRemoteModule: true, // 可以使用remote方法
            webSecurity: false, // 它将禁用同源策略 (通常用来测试网站), 如果此选项不是由开发者设置的默认为true
            defaultMonospaceFontSize: 16, //页面字体默认为16
            minimumFontSize: 12, //页面字体最小为12
            //   v8CacheOptions: "bypassHeatCheckAndEagerCompile", //v8CacheOptions 强制 blink 使用 v8 代码缓存策略  除了编译是及时的。 默认策略是 code。
        },
    });

    //   // 和自己本地vue项目启动的地址保持一致
    //   mainWindow.loadURL("http://localhost:5173");

    //判断是否为开发环境，开发环境需要更换url
    mainWindow.loadURL(
        NODE_ENV === "development"
            ? "http://localhost:5173"
            : `file://${path.join(__dirname, "../dist/index.html")}`
    );
    // 设置窗口菜单栏
    mainWindow.setMenu(null);
    //打开
    mainWindow.show();

    // 打开开发工具
    if (NODE_ENV === "development") {
        mainWindow.webContents.openDevTools();
    }

    //   // 当窗口被关闭时释放资源
    //   mainWindow.on("closed", function () {
    //     mainWindow = null;
    //   });

    // 监听窗口大小变化事件
    mainWindow.on('resize', () => {
        const { width, height } = mainWindow.getBounds();
        const x = Math.round((screen.getPrimaryDisplay().bounds.width - width) / 2);
        const y = Math.round((screen.getPrimaryDisplay().bounds.height - height) / 2);
        mainWindow.setBounds({ x, y });
    });
};

// 应用准备就绪，加载窗口
app.whenReady().then(() => {
    createWindow();

    // mac 上默认保留一个窗口
    app.on("activate", () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

// 关闭所有窗口 ： 程序退出 ： windows & linux
app.on("window-all-closed", () => {
    if (process.platform !== "darwin") app.quit();
});