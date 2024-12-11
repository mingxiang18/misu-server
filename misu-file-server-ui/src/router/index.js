import { createRouter, createWebHistory } from 'vue-router';
import { getToken } from '@/api/auth/token'
import Index from '@/components/Index.vue';
import HelloWorld from '@/components/HelloWorld.vue';
import Login from '@/components/login/Login.vue';
import UserTest from '@/components/user/UserManagement.vue';
import PublicFileServer from '@/components/fileServer/PublicFileServer.vue';
import PrivateFileServer from '@/components/fileServer/PrivateFileServer.vue';

const routes = [
    {
        path: '/',
        component: Index,
        children: [
            { path: '', component: HelloWorld, name: 'home' },
            { path: '/userManagement', component: UserTest, name: 'userTest' },
            { path: '/fileServer/publicDirectory/:path*', component: PublicFileServer, name: 'PublicFileServer' },
            { path: '/fileServer/privateDirectory/:path*', component: PrivateFileServer, name: 'PrivateFileServer' },
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
    const isAuthenticated = getToken()

    if (to.path !== '/login' && !isAuthenticated) {
        // 未登录时跳转到登录页
        next('/login');
    } else {
        // 继续路由
        next();
    }
});

export default router;
