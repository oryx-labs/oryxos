# Acceptance: 033 微信客服

**对照**: [plan](./plan.md)、[research](./research.md)

## PLAN

- [x] 与 `wecom` 应用机器人区分写清  
- [x] 入站/出站 API 与 48h/5 条钉死  
- [x] 产品确认可 BUILD  

## BUILD

- [x] 模块 `oryxos-channel-weixin-kf` + 单测（验签加解密、sync 归一化、窗外/超 5 条、契约档）  
- [x] Setup + `channels.yaml.example` + Runtime `type: weixin_kf`  
- [x] 媒体：`media/get` 落盘 + Vision / `read_file` / Whisper；`voice_format=0`（AMR）；sync cursor 排空；同会话连续媒体合并  
- [x] 真机：文本 / 图 / PDF / 视频音轨 / 语音（AMR）往返 OK（PKCS#7=32、`service_state` 48002 软跳过）  

