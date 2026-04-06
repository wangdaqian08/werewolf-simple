音频映射


┌─────────────────────┬──────────────────┐
│ 游戏阶段/子阶段     │ 音频文件         │
├─────────────────────┼──────────────────┤
│ NIGHT 阶段开始      │ 天黑请闭眼.mp3   │
│ DAY 阶段开始        │ 天亮了.mp3       │
│ NIGHT.WEREWOLF_PICK │ 狼人请睁眼.mp3   │
│ 狼人行动结束        │ 狼人请闭眼.mp3   │
│ NIGHT.SEER_PICK     │ 预言家请睁眼.mp3 │
│ 预言家行动结束      │ 预言家请闭眼.mp3 │
│ NIGHT.WITCH_ACT     │ 女巫请睁眼.mp3   │
│ 女巫行动结束        │ 女巫请闭眼.mp3   │
│ NIGHT.GUARD_PICK    │ 守卫请睁眼.mp3   │
│ 守卫行动结束        │ 守卫请闭眼.mp3   │
└─────────────────────┴──────────────────┘

使用方式

音频会自动根据游戏阶段播放，无需手动调用。如果需要控制音量：

const { setVolume, getVolume } = useAudioService()

// 设置音量（0-1）
setVolume(0.5)

// 获取当前音量
const volume = getVolume()
