# 舊式 Package Configuration

本指南說明既有 Package 的本地路徑和 Server 註冊方式。

## 在本地選取 Package

除非 `ATT_PACKAGE_ROOT` 指定另一個 root，launcher 會使用 `packages/default`。`package.yaml` 定義 Package metadata 和 resource roots；共用 Runtime Configuration 仍放在 `ATT_HOME/config/`。

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh validate --package
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh debug template PAYMENT_INVOKE
```

Scenario 放在 Package 以外時，請傳入 Package-relative 路徑，例如 `../../examples/legacy/load/closed-minimal.yaml`。`--config config/config.yaml` 等 runtime configuration option 仍相對於 `ATT_HOME`。

## 在 Server 註冊 Package

`packages/legacy/package.yaml` 令該目錄成為有效 Package，但不會自動加入 Server registry。管理員必須在 Server configuration 的 `packages.allowedRoots` 加入 root，並在 `packages.entries` 加入 entry：

```yaml
packages:
  allowedRoots:
    - /opt/att/packages
  entries:
    default: /opt/att/packages/default
    legacy: /opt/att/packages/legacy
```

請依部署流程重新啟動或載入 Server configuration。Studio 和 Server API 只會顯示已配置的 Package ID。Source browsing 和 source edit 亦要求呼叫者具備已配置的 Package role；在 registry 登記不代表使用者已取得 editor 權限。

所有已配置 Package root 必須位於核准的 allowed root 之下。使用 server 絕對路徑；不要讓使用者可編輯的 manifest 選擇 Server registry entry 或 infrastructure setting。

## 分開 Package 與 Runtime Configuration

Package resource 包括 Flow、Template、Testdata、Tool 和 helper descriptor。共用 environment profile 和 credential 仍放在 Server 或 Local runtime configuration。Package 的 `package.yaml` 不可覆蓋 Server authorization、allowed root、worker policy 或 secret storage。

## 較早的 Workbook configuration 欄位

較早的 workbook package 可能仍包含 `templates.root` 和 `testcase.root`。這些欄位已棄用；新 package 會在 `package.yaml` 宣告 resource roots。既有 workbook Run 可透過 `report.fileNamePattern` 和 `${suiteName}` 使用來源 workbook 名稱，將結果寫入 Run 的 `workbooks/` 目錄。Native Case result workbook 不使用這些設定。相關 Package 檔案見[舊版 authoring 指南](authoring.zh.md)。
