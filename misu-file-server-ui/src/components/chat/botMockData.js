// 阶段0 UI 效果图用的假数据；阶段1 接入服务端后整文件删除。
// 仅用于驱动会话列表 / 聊天 / 建群 / 成员面板的视觉呈现，无任何网络请求。

export const currentUser = { userId: '1001', userName: '我', nickName: '我', avatar: '' }

export const BOT = { userId: 'bot', userName: '冥想bb', nickName: '冥想bb', botFlag: true }

// 通讯录候选用户（建群选人用）
export const directory = [
  { userId: '1002', userName: 'linwan', nickName: '林晚', avatar: '' },
  { userId: '1003', userName: 'zhouye', nickName: '周野', avatar: '' },
  { userId: '1004', userName: 'suman', nickName: '苏曼', avatar: '' },
  { userId: '1005', userName: 'chenmo', nickName: '陈墨', avatar: '' },
  { userId: '1006', userName: 'verifybot', nickName: '阿测', avatar: '' },
  { userId: '1007', userName: 'jiangli', nickName: '江离', avatar: '' }
]

const u = (id) => directory.find((x) => x.userId === id)

export const conversations = [
  {
    id: 'g1',
    type: 'GROUP',
    title: '深夜茶话会',
    ownerUserId: '1001',
    members: [
      { ...currentUser, role: 'OWNER' },
      { ...u('1002'), role: 'MEMBER' },
      { ...u('1003'), role: 'MEMBER' },
      { ...u('1004'), role: 'MEMBER' },
      { ...BOT, role: 'MEMBER' }
    ],
    lastSenderName: '林晚',
    lastMessage: '@冥想bb 来首适合深夜听的歌',
    lastMessageAt: '21:32',
    messages: [
      { id: 'm1', senderType: 'USER', sender: u('1002'), content: [{ type: 'text', data: '今晚都还醒着吗？' }], time: '21:20' },
      { id: 'm2', senderType: 'USER', sender: u('1003'), content: [{ type: 'text', data: '在的，刚加完班 🥱' }], time: '21:22' },
      { id: 'm3', senderType: 'USER', sender: currentUser, isSelf: true, content: [{ type: 'text', data: '辛苦啦，泡杯热茶歇会儿' }], time: '21:24' },
      { id: 'm4', senderType: 'USER', sender: u('1004'), content: [{ type: 'text', data: '这张照片是我刚拍的窗外' }, { type: 'netImage', data: 'https://picsum.photos/seed/misu-night/320/200' }], time: '21:28' },
      { id: 'm5', senderType: 'USER', sender: u('1002'), content: [{ type: 'text', data: '@冥想bb 来首适合深夜听的歌', at: ['bot'] }], time: '21:32' },
      { id: 'm6', senderType: 'BOT', sender: BOT, content: [{ type: 'text', data: '夜深了，给你们推荐一首《River Flows in You》——钢琴声像月光淌过窗台，配一杯温水刚刚好 🎹\n需要的话我可以再排几首同类的歌单。' }], time: '21:32' }
    ]
  },
  {
    id: 'p1',
    type: 'PRIVATE',
    title: '冥想bb',
    ownerUserId: '1001',
    members: [{ ...currentUser, role: 'OWNER' }, { ...BOT, role: 'MEMBER' }],
    lastSenderName: '',
    lastMessage: '随时找我聊聊都可以哦～',
    lastMessageAt: '昨天',
    messages: [
      { id: 'p1m1', senderType: 'BOT', sender: BOT, content: [{ type: 'text', data: '这里是冥想bb，有什么可以帮你的吗？' }], time: '昨天 22:01' },
      { id: 'p1m2', senderType: 'USER', sender: currentUser, isSelf: true, content: [{ type: 'text', data: '最近有点睡不好' }], time: '昨天 22:02' },
      { id: 'p1m3', senderType: 'BOT', sender: BOT, content: [{ type: 'text', data: '试试睡前 4-7-8 呼吸法：吸气 4 秒、屏息 7 秒、呼气 8 秒，重复四轮。\n要不要我陪你做一次引导？' }], time: '昨天 22:02' }
    ]
  },
  {
    id: 'g2',
    type: 'GROUP',
    title: '冥想打卡小组',
    ownerUserId: '1005',
    members: [
      { ...u('1005'), role: 'OWNER' },
      { ...currentUser, role: 'MEMBER' },
      { ...u('1004'), role: 'MEMBER' },
      { ...BOT, role: 'MEMBER' }
    ],
    lastSenderName: '陈墨',
    lastMessage: '今天 day 7 打卡完成 ✅',
    lastMessageAt: '周一',
    messages: [
      { id: 'g2m1', senderType: 'USER', sender: u('1005'), content: [{ type: 'text', data: '今天 day 7 打卡完成 ✅' }], time: '周一 07:30' },
      { id: 'g2m2', senderType: 'USER', sender: currentUser, isSelf: true, content: [{ type: 'text', data: '坚持得真好！' }], time: '周一 07:35' }
    ]
  }
]
