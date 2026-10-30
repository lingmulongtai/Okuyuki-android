# Okuyuki Android

Galaxyの3D写真を、同じWi-FiにいるMeta Questから見るためのスマホ側アプリ。
写真はスマホに置いたまま、Questは必要な写真だけを一時的に受け取ります。

Companion: https://github.com/lingmulongtai/Okuyuki-quest
Project: https://github.com/users/lingmulongtai/projects/2

## GalaxyとQuest 3Sで試す

**開発版で360°写真・動画の共有を試す場合:** [Galaxy・Quest実機テスト用プレビュー](docs/DEVICE-PREVIEW.md)をご覧ください。

配布中のアルファは **v0.1.0-alpha.12-preview** です。Galaxyは **Android 16** が必要です。

| 入れる端末 | APKの直接ダウンロード | 保存先 |
| --- | --- | --- |
| Galaxy | [Android版APK](https://github.com/lingmulongtai/Okuyuki-android/releases/download/v0.1.0-alpha.12-preview/Okuyuki-android-v0.1.0-alpha.12-preview.apk) | Galaxy |
| Quest 3S／3 | [Quest版APK](https://github.com/lingmulongtai/Okuyuki-quest/releases/download/v0.1.0-alpha.12-preview/Okuyuki-quest-v0.1.0-alpha.12-preview.apk) | Windows PC |

1. **Galaxy**: Android版APKを開き、インストール元の許可を与えて入れます。Okuyukiの写真アクセスを許可し、ライブラリに試したい3D写真があることを確認します。
2. **Quest**: Meta Horizonアプリで開発者モードをオンにし、PCとUSB接続してQuest内のUSBデバッグを許可します。PCからADBでQuest版APKを入れ、アプリライブラリーの「提供元不明」から **Okuyuki Quest** を開きます。
3. **接続**: 両端末を同じWi-Fiに接続します。Galaxyの「Quest」タブで **「Questとつなぐ」をオン** にし、Questでスマホを選びます。見つからない場合はGalaxyの **IPアドレス:ポート** をQuestに入力します。
4. **承認と閲覧**: 両画面の4桁の番号が同じことを確認し、Galaxyで「許可」を押します。Questの一覧から写真を選びます。終了時はQuestアプリを閉じ、Galaxyの待機をオフにします。

**[初回の設定から詳しく読む → インストール・接続ガイド](docs/INSTALL.md)**

ガイドにはMetaアカウントの初回設定、Windows用ADBとドライバ、コピーできるPowerShellコマンド、Galaxyの自動ブロッカー、接続できない場合の確認方法があります。
通常の写真は3D一覧の対象外です。GalaxyのSamsungステレオHEICの原本を使ってください。
実機での原本デコード・左右の立体表示は未検証です。アルファの通信は承認付きHTTPなので、信頼できる自宅Wi-FiまたはGalaxyのテザリングで試してください。

## このプレビューで試せる機能

以下は実機検証前の実験機能です。

開発中のAndroid版をGalaxyで試すには、[実機テスト用プレビューの導入手順](docs/DEVICE-PREVIEW.md)をご覧ください。

- 設定で日本語・英語・端末の言語、ライト・ダーク・端末のテーマを選択できます。
- ライブラリの「360°メディアを開く」から、結合済みの2:1の360°写真や360° MP4をスマートフォン上で表示できます。写真は表示時に最大4096ピクセルに縮小されます。部分的に切り抜かれたパノラマには未対応です。
- 同じ入口からDJI Osmo 360の `.LRF` を選ぶと、左右の魚眼映像を使う実験的なプレビューを試せます。ドラッグで視点を変更でき、再生・一時停止・シークに対応します。対応するLRFではカメラの校正値を使う実験的なレンズ補正を適用し、読み取れない場合は近似表示に戻ります。対応するLRFでは、画面のスイッチから実験的な回転平滑化も試せます。時刻を照合できない場合は自動でオフになります。
- `.OSV` の2映像を使用する実験的な直接プレビューでは、対応する校正情報がある場合はレンズ校正を適用します。対応する姿勢情報を利用した実験的な回転平滑化を選べます。実機でのデコードと同期は未検証です。
- 結合済み360°写真・MP4とOSV・LRFをQuestへ共有し、通常ウィンドウで表示できます。対応するOSV・LRFの校正値もQuestへ渡します。結合済みMP4とLRF・OSVではQuestの実験的な頭部追従による没入表示も選べます。Galaxy・Quest実機での動作は未検証です。

## Build
JDK 17、Android SDK 36。`./gradlew assembleDebug`（Windows: `gradlew.bat`）。
WindowsではASCIIのパスにcloneまたはjunctionを作ってビルドしてください。

## Alpha status
仕様書の「最初の版」を対象とするアルファです。[範囲と実機チェック](docs/ALPHA-SCOPE.md)。
実機検証の完了を示すものではありません。v1.0の機能は上記の範囲と区別します。
元の仕様書に含まれる写真はこのリポジトリには公開しません。

[署名付きAPKのReleases](https://github.com/lingmulongtai/Okuyuki-android/releases) · [インストールと接続](docs/INSTALL.md) · [リリース内容](docs/RELEASE-NOTES.md)

[検証記録](docs/VALIDATION.md)

### アプリの更新

起動時に新しいプレビュー版を確認し、安全性を検証したAPKをダウンロードします。「設定」から手動確認もできます。更新が見つかったらインストールを選び、OSの確認に従ってください。初回はOkuyukiからのインストールを許可する必要があります。alpha.9以前には更新機能がないため、alpha.9以前からは下記の手順で手動更新してください。Questのアプリ内インストーラーは実機未検証で、利用できない場合はPCから `adb install -r` してください。

GitHub API の利用上限に達した場合も、公開リリース情報とチェックサムを使って更新を確認します。alpha.10・alpha.11で更新エラーが続く場合は、この版を一度手動で上書きしてください。
