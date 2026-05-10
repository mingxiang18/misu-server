import { createRouter, createWebHistory } from 'vue-router';
import { getRefreshToken, getToken } from '@/api/auth/token'
import Index from '@/components/Index.vue';
import Home from '@/components/Home.vue';
import Login from '@/components/login/Login.vue';
import UserTest from '@/components/user/UserManagement.vue';
import PublicFileServer from '@/components/fileServer/PublicFileServer.vue';
import PrivateFileServer from '@/components/fileServer/PrivateFileServer.vue';
import VideoRoom from '@/components/fileServer/VideoRoom.vue';
import TorrentManagement from '@/components/fileServer/TorrentManagement.vue';
import VideoTranscodeManagement from '@/components/fileServer/VideoTranscodeManagement.vue';
import TrashView from '@/components/fileServer/TrashView.vue';
import SharesView from '@/components/fileServer/SharesView.vue';
import SharedDownload from '@/components/fileServer/SharedDownload.vue';
import AuditLogView from '@/components/fileServer/AuditLogView.vue';
import BotChat from '@/components/bot/BotChat.vue';
import EpubViewer from '@/components/utils/EpubViewer.vue';
import PdfViewer from '@/components/utils/PdfViewer.vue';
import LanguageLearn from "@/components/languageLearn/LanguageLearn.vue";

const routes = [
    {
        path: '/',
        component: Index,
        children: [
            { path: '', component: Home, name: 'home' },
            { path: 'userManagement', component: UserTest, name: 'userTest' },
            { path: 'languageLearn', component: LanguageLearn, name: 'LanguageLearn' },
            { path: 'fileServer/publicDirectory/:path*', component: PublicFileServer, name: 'PublicFileServer' },
            { path: 'fileServer/privateDirectory/:path*', component: PrivateFileServer, name: 'PrivateFileServer' },
            { path: 'fileServer/videoRoom/:roomId', component: VideoRoom, name: 'VideoRoomFromId' },
            { path: 'fileServer/videoRoom', component: VideoRoom, name: 'VideoRoomFromHistory' },
            { path: 'fileServer/torrentManagement', component: TorrentManagement, name: 'TorrentManagement' },
            { path: 'fileServer/videoTranscodeManagement', component: VideoTranscodeManagement, name: 'VideoTranscodeManagement' },
            { path: 'fileServer/trash', component: TrashView, name: 'TrashView' },
            { path: 'fileServer/shares', component: SharesView, name: 'SharesView' },
            { path: 'fileServer/audit', component: AuditLogView, name: 'AuditLogView' },
            { path: 'fileServer/epubViewer', component: EpubViewer, name: 'EpubViewer' },
            { path: 'fileServer/pdfViewer', component: PdfViewer, name: 'PdfViewer' },
            { path: 'bot', component: BotChat, name: 'BotChat' },
        ],
    },
    {   path: '/login', component: Login },
    {   path: '/share/:token', component: SharedDownload, name: 'SharedDownload', meta: { anonymous: true } },
];

const router = createRouter({
    history: createWebHistory(),
    routes,
});

// 路由守卫
router.beforeEach((to, from, next) => {
    const isAuthenticated = getToken() || getRefreshToken()
    const isAnonymous = to.matched.some(r => r.meta && r.meta.anonymous);

    if (to.path !== '/login' && !isAnonymous && !isAuthenticated) {
        // 未登录时跳转到登录页（外链分享等匿名页放行）
        next('/login');
    } else {
        next();
    }
});

export default router;
