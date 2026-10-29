# Okuyuki Android

Galaxyの3D写真を、同じWi-FiにいるMeta Questから見るためのスマホ側アプリ。
写真はスマホに置いたまま、Questは必要な写真だけを一時的に受け取ります。

Companion: https://github.com/lingmulongtai/Okuyuki-quest
Project: https://github.com/users/lingmulongtai/projects/2

## GalaxyとQuest 3Sで試す

配布中のアルファは **v0.1.0-alpha.1** です。Galaxyは **Android 16** が必要です。

| 入れる端末 | APKの直接ダウンロード | 保存先 |
| --- | --- | --- |
| Galaxy | [Android版APK](https://github.com/lingmulongtai/Okuyuki-android/releases/download/v0.1.0-alpha.1/Okuyuki-android-v0.1.0-alpha.1.apk) | Galaxy |
| Quest 3S／3 | [Quest版APK](https://github.com/lingmulongtai/Okuyuki-quest/releases/download/v0.1.0-alpha.1/Okuyuki-quest-v0.1.0-alpha.1.apk) | Windows PC |

1. **Galaxy**: Android版APKを開き、インストール元の許可を与えて入れます。Okuyukiの写真アクセスを許可し、ライブラリに試したい3D写真があることを確認します。
2. **Quest**: Meta Horizonアプリで開発者モードをオンにし、PCとUSB接続してQuest内のUSBデバッグを許可します。PCからADBでQuest版APKを入れ、アプリライブラリーの「提供元不明」から **Okuyuki Quest** を開きます。
3. **接続**: 両端末を同じWi-Fiに接続します。Galaxyの「Quest」タブで **「Questとつなぐ」をオン** にし、Questでスマホを選びます。見つからない場合はGalaxyの **IPアドレス:ポート** をQuestに入力します。
4. **承認と閲覧**: 両画面の4桁の番号が同じことを確認し、Galaxyで「許可」を押します。Questの一覧から写真を選びます。終了時はQuestアプリを閉じ、Galaxyの待機をオフにします。

**[初回の設定から詳しく読む → インストール・接続ガイド](docs/INSTALL.md)**

ガイドにはMetaアカウントの初回設定、Windows用ADBとドライバ、コピーできるPowerShellコマンド、Galaxyの自動ブロッカー、接続できない場合の確認方法があります。
通常の写真は3D一覧の対象外です。GalaxyのSamsungステレオHEICの原本を使ってください。
実機での原本デコード・左右の立体表示は未検証です。アルファの通信は承認付きHTTPなので、信頼できる自宅Wi-FiまたはGalaxyのテザリングで試してください。

## 開発中の機能

配布中のv0.1.0-alpha.1には、以下の開発中の変更は含まれていません。

- 設定で日本語・英語・端末の言語、ライト・ダーク・端末のテーマを選択できます。
- ライブラリの「360°メディアを開く」から、結合済みの2:1の360°写真や360° MP4をスマートフォン上で表示できます。写真は表示時に最大4096ピクセルに縮小されます。部分的に切り抜かれたパノラマには未対応です。
- 同じ入口からDJI Osmo 360の `.LRF` を選ぶと、左右の魚眼映像を使う実験的なプレビューを試せます。ドラッグで視点を変更でき、再生・一時停止・シークに対応します。レンズの個別補正、手ぶれ補正、つなぎ目の調整は未実装です。
- これらの操作や表示はAndroid実機では未検証です。Questでの360°写真・動画の再生と、`.OSV` の直接再生にはまだ対応していません。

## Build
JDK 17、Android SDK 36。`./gradlew assembleDebug`（Windows: `gradlew.bat`）。
WindowsではASCIIのパスにcloneまたはjunctionを作ってビルドしてください。

## Alpha status
仕様書の「最初の版」を対象とするアルファです。[範囲と実機チェック](docs/ALPHA-SCOPE.md)。
実機検証の完了を示すものではありません。v1.0の機能は上記の範囲と区別します。
元の仕様書に含まれる写真はこのリポジトリには公開しません。

[署名付きAPKのReleases](https://github.com/lingmulongtai/Okuyuki-android/releases) · [インストールと接続](docs/INSTALL.md) · [リリース内容](docs/RELEASE-NOTES.md)

[検証記録](docs/VALIDATION.md)
