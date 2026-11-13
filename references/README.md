# 本地参考源码

| 项目 | 本地目录 | 上游 | 目标基线 |
| --- | --- | --- | --- |
| MaiBot | [MaiBot](MaiBot) | https://github.com/Mai-with-u/MaiBot.git | `2a9af62cec37035d29237acbe2cf3adceb700b27` |

状态：已完成浅克隆并以 detached HEAD 固定到上述提交；工作区干净，主项目忽略规则生效。已将源码记录中涉及的 14 份 MaiBot 文件逐一计算 Git blob SHA，与 R07—R08、R22—R27、R48—R49、R59—R62 全部匹配。

参考目录是独立 Git checkout，已通过主项目 .gitignore 排除，不作为应用源码或依赖提交。参考项目不在本任务中安装或运行；默认保留记录的提交，不自动 pull 或更新。

继续工作先读 [行为 agent.md](../companion-agent/behavior/agent.md) 或 [人格 agent.md](../companion-agent/personality/agent.md)。两份模块记录给出结论、文件、函数和行号；[源码索引](../companion-agent/REFERENCES.md)保留 blob SHA 与历史依据。先定位再局部读取，不重复远程获取相同文件。

本地缺失时可恢复（在项目根目录执行）：

```bash
git clone --depth 1 --no-checkout https://github.com/Mai-with-u/MaiBot.git references/MaiBot
git -C references/MaiBot fetch --depth 1 origin 2a9af62cec37035d29237acbe2cf3adceb700b27
git -C references/MaiBot checkout --detach 2a9af62cec37035d29237acbe2cf3adceb700b27
```

行号对应上述提交。若明确需要升级，先取得新提交，再重核受影响结论、函数范围与文件 blob SHA，并更新本文件和模块记录。记录日期：2026-10-29。

## 新候选选读缓存

[候选研究及源码范围](../companion-agent/behavior/CANDIDATES.md)记录本轮追加检索。references/source-cache 按仓库、提交、原路径保存已获取的 8 份关键源码，全部核对 Git blob SHA；它是局部快照，不能当成完整 checkout。该缓存同样被主项目忽略。候选被选定后，再获取完整 Git 仓库；后续避免重复远程获取同一内容。
