import { createRouter, createWebHistory } from 'vue-router';
import { getRefreshToken, getToken } from '@/api/auth/token'
import Index from '@/components/Index.vue';
import HelloWorld from '@/components/HelloWorld.vue';
import Login from '@/components/login/Login.vue';
import UserTest from '@/components/user/UserManagement.vue';
import PublicFileServer from '@/components/fileServer/PublicFileServer.vue';
import PrivateFileServer from '@/components/fileServer/PrivateFileServer.vue';
import VideoRoom from '@/components/fileServer/VideoRoom.vue';
import TorrentManagement from '@/components/fileServer/TorrentManagement.vue';
import VideoTranscodeManagement from '@/components/fileServer/VideoTranscodeManagement.vue';
import BotChat from '@/components/bot/BotChat.vue';
import EpubViewer from '@/components/utils/EpubViewer.vue';
import LanguageLearn from "@/components/languageLearn/LanguageLearn.vue";

const routes = [
    {
        path: '/',
        component: Index,
        children: [
            { path: '', component: HelloWorld, name: 'home' },
            { path: 'userManagement', component: UserTest, name: 'userTest' },
            { path: 'languageLearn', component: LanguageLearn, name: 'LanguageLearn' },
            { path: 'fileServer/publicDirectory/:path*', component: PublicFileServer, name: 'PublicFileServer' },
            { path: 'fileServer/privateDirectory/:path*', component: PrivateFileServer, name: 'PrivateFileServer' },
            { path: 'fileServer/videoRoom/:roomId', component: VideoRoom, name: 'VideoRoomFromId' },
            { path: 'fileServer/videoRoom', component: VideoRoom, name: 'VideoRoomFromHistory' },
            { path: 'fileServer/torrentManagement', component: TorrentManagement, name: 'TorrentManagement' },
            { path: 'fileServer/videoTranscodeManagement', component: VideoTranscodeManagement, name: 'VideoTranscodeManagement' },
            { path: 'fileServer/epubViewer', component: EpubViewer, name: 'EpubViewer' },
            { path: 'bot', component: BotChat, name: 'BotChat' },
        ],
    },
    {   path: '/login', component: Login },
];

const router = createRouter({
    history: createWebHistory(),
    routes,
});

// 路由守卫
router.beforeEach((to, from, next) => {
    const isAuthenticated = getToken() || getRefreshToken()
    // const isAuthenticated = true

    if (to.path !== '/login' && !isAuthenticated) {
        // 未登录时跳转到登录页
        next('/login');
    } else {
        // 继续路由
        next();
    }
});

export default router;
