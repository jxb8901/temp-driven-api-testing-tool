# Reference Manual 模組化來源

這些模組是目前 ATT Reference Manual 的正式可編輯來源。

- 英文來源：`docs/reference/`
- 中文來源：`docs/reference.zh/`
- 固定章節順序：`docs/reference-manifest.txt`
- 生成後的完整文件：`docs/generated/`
- 舊的 `docs/09_Reference_Manual_V3*` 路徑保留為自動生成的相容輸出。

修改 source modules 後執行 `python3 tools/build_reference_manual.py`。Generator 從 `pom.xml` 讀取 ATT 版本，需要 Python 3 與 Pandoc，按 manifest 的同一順序生成中英文完整文件並刷新 compatibility outputs。CI 使用 `python3 tools/build_reference_manual.py --check` 檢查缺失模組與 stale generated outputs。

`build.sh` 亦會在 release gate 前執行 generator，因此 release package 中的 Reference Manual 是由 modular sources 組裝，而不是由手工維護的巨型 Markdown 文件複製而來。

Issue #41 只負責結構性 modularization。現有規範內容的 transitional placement 會保留到 #42 按 `docs/documentation-architecture.md` 完成語義上的遷移與重寫。
