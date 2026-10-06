# AutoBuild addon (Meteor, 1.21.11) – bản có tự di chuyển

CHƯA ĐƯỢC BIÊN DỊCH. Đây là bản viết lại vì không có mã nguồn gốc.

## Build
1. Cài JDK 21.
2. Lấy meteor-addon-template (nhánh 1.21.11): copy gradlew, gradle/, và đối chiếu
   gradle.properties / build.gradle (yarn_mappings, meteor_version).
3. Bỏ jar Baritone API vào libs/ (sửa tên trong build.gradle).
4. Chạy `./gradlew build` -> jar nằm trong build/libs/ (dùng file không có -sources).
5. Bỏ jar vào mods cùng Fabric API, Meteor Client, Baritone.

## Khác bản 1.0.0
- Thêm auto-move (Baritone GoalNear), xây từ thấp lên cao.
- Bỏ: rotation-spoof, smart-placement, easy-redstone, đặt block theo hướng (Easy Place).
- Không đọc .litematic / Litematica.
- Block phải có sẵn trong hotbar.

## Build bằng GitHub Actions
Đẩy cả thư mục (kèm .github/ và libs/baritone jar) lên một repo GitHub.
Tab Actions -> workflow "Build" -> tải artifact "autobuild-addon" (file .jar).
