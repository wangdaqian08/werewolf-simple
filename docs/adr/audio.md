音频映射


┌─────────────────────┬──────────────────┐
│ 游戏阶段/子阶段     │ 音频文件         │
├─────────────────────┼──────────────────┤
│ NIGHT 阶段开始      │ goes_dark_close_eyes.mp3   │
│ DAY 阶段开始        │ day_time.mp3       │
│ NIGHT.WEREWOLF_PICK │ wolf_open_eyes.mp3   │
│ 狼人行动结束        │ wolf_close_eyes.mp3   │
│ NIGHT.SEER_PICK     │ seer_open_eyes.mp3 │
│ 预言家行动结束      │ seer_close_eyes.mp3 │
│ NIGHT.WITCH_ACT     │ witch_open_eyes.mp3   │
│ 女巫行动结束        │ witch_close_eyes.mp3   │
│ NIGHT.GUARD_PICK    │ guard_open_eyes.mp3   │
│ 守卫行动结束        │ guard_close_eyes.mp3   │
└─────────────────────┴──────────────────┘

使用方式

音频会自动根据游戏阶段播放，无需手动调用。如果需要控制音量：

const { setVolume, getVolume } = useAudioService()

// 设置音量（0-1）
setVolume(0.5)

// 获取当前音量
const volume = getVolume()
