# Android実機テスト用プレビュー

このプレビューは開発途中のAndroid版です。公開中のアルファリリースとは別に、GitHub Actionsの成果物として14日間保存します。GalaxyにはAndroid 16以降が必要です。

1. [Android checks](https://github.com/lingmulongtai/Okuyuki-android/actions/workflows/ci.yml) を開き、`codex/android-quality` の手動実行が成功した回を選びます。GitHubへのログインが必要です。
2. 実行ページ下部の **Artifacts** から `android-device-preview` をダウンロードして展開します。`Okuyuki-android-0.1.0-alpha.2-preview.apk` がGalaxy用です。
3. GalaxyにAPKを転送して開き、インストール元の許可を求められたら許可してインストールします。署名確認を通過した成果物だけが保存されるため、配布済みAndroid版と同じ署名で、バージョン番号も上がっています。既存アプリを削除せず更新してください。
4. ライブラリの「360°メディアを開く」から、結合済み2:1の360°写真、結合済み360° MP4、またはOsmo 360のLRFを選びます。LRFはレンズ補正・手ぶれ補正のない実験的な表示です。

OSVの直接再生、360°メディアのQuestへの転送・再生、切り抜きパノラマへの対応は含まれていません。画面操作や動画表示はGalaxy・Quest実機で未検証です。通常のQuest接続は従来のステレオHEIC用で、Quest版 `0.1.0-alpha.1` を使用します。

`SHA256SUMS.txt` と `signature.txt` はAPKと同じ成果物に含まれます。APKの署名が既存版と一致しない、またはインストールが失敗する場合は、既存アプリを削除せずに作業を止めてください。端末側の詳しいインストール方法は[インストール・接続ガイド](INSTALL.md)をご覧ください。
