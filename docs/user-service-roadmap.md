# Roadmap triển khai User Service MVP

Cập nhật: 02/11/2026. Nguồn yêu cầu: `BA - SOA.docx`, phần User Service và Contract chung MVP v2.1; `Prompt_User_Service.md`. Trạng thái bên dưới phân biệt phần đã kiểm chứng và công việc còn lại.

## Nguyên tắc và hiện trạng

- Giữ Java 21/Spring Boot 3.5.x/Maven, PostgreSQL/Flyway và port 8081. Chỉ sửa repo User; không đọc DB hoặc tự sửa code service khác.
- Ưu tiên chỉ đạo mới nhất → Conflict đã chốt → Contract v2.1 → rule → service spec. Không tự thêm API/role/nghiệp vụ ngoài BA.
- Schema đã có không đồng nghĩa nghiệp vụ đã được triển khai. Đã có media storage/API, transactional outbox, Kafka relay, email worker, profile bootstrap và reputation consumer cho penalty/reward/reversal. Listener reputation mặc định tắt đến khi Payment bàn giao fixture/producer mới với reversal thưởng version2; delivery cần cấu hình môi trường.
- Các phase dưới tách nhỏ nhóm công việc trong prompt gốc để dễ nghiệm thu: Phase 0 tương ứng nền; Phase 1 tương ứng auth; Phase 2–4 tương ứng profile/Admin/media; Phase 5 tương ứng outbox; Phase 6 tương ứng reputation; Phase 7 kiểm chứng tích hợp và bàn giao.
- Mỗi phase có code, migration nếu cần, test phù hợp, fixtures và cập nhật README/implementation-decisions. Không sửa migration đã áp dụng; dùng migration mới. Không commit/push hoặc thay đổi repo khác nếu lượt thực hiện chưa được yêu cầu.

| Phase | Phạm vi | Trạng thái |
| --- | --- | --- |
| 0 | Nền project, schema, lỗi API, access-state | Đã có code và kiểm chứng local |
| 1 | Authentication, OTP, JWT, refresh/logout, rate limit, seed | Đã có code và kiểm chứng; cấu hình Admin/SMTP thật còn tùy môi trường |
| 2 | Private/public profile | Đã triển khai, kiểm chứng và ghi outbox; relay đã có ởPhase5 |
| 3 | Admin quản lý account và khóa/mở | Đã hoàn thành và kiểm chứng API/policy/outbox; delivery Kafka/email thuộc Phase 5 |
| 4 | Upload/read avatar | Đã hoàn thành và kiểm chứng; quyết định ADMIN đã được người dùng chấp thuận |
| 5 | Outbox relay, profile event, email khóa/mở | Đã triển khai và kiểm chứng local với Kafka/PostgreSQL/Mailpit; tích hợp owner thật thuộc Phase7 |
| 6 | Reputation consumer và event kết quả | Đã hoàn thành phía User/local, kiểm chứng reversal/cap/dedup/lock/rollback/retry/DLT; tích hợp Payment thật thuộc Phase7 |
| 7 | Kiểm thử tích hợp, Docker và bàn giao | Có kiểm chứng User local; chưa nghiệm thu tích hợp/liên service và môi trường bàn giao |

Kiểm chứng ngày02/11/2026: `clean verify` **BUILD SUCCESS, 168 test** (43 unit/Web/security +125 integration), 0 failure/error/skipped. Phase6 có35 test trực tiếp: 7 validator/policy/fixtures, 23 PostgreSQL domain và 5 Kafka reliability. Bao gồm reversal actualDelta/clamp0–10, double reversal, thiếu gốc/replay, scope/version, giữ flag/status, Admin mở thủ công, concurrent/rollback và crash sau commit trước ACK. Các regression Auth/Admin/profile/media/JAR restart/outbox/SMTP tiếp tục đạt. Log local `.tools/phase6-complete-verify.log`; XML reports tại target/surefire-reports và target/failsafe-reports. **Phase6 hoàn thành trong phạm vi User/local**; Payment bàn giao producer/fixture mới cho reversal thưởng version2 và tích hợp owner thực tế, Docker/deployment/backup/SMTP production thuộc Phase7.

## Phase 0 — Nền project và persistence

**Đã có:** Maven Wrapper, cấu hình local, PostgreSQL/Flyway, schema nền và các bảng v2.1; lỗi JSON thống nhất; internal access-state đọc primary DB, service key riêng caller, no-store, fail closed; health/readiness và smoke test.

**Giữ làm baseline:** không phá migration V1/V2, không đưa secret hoặc dữ liệu môi trường vào Git. PostgreSQL local của người dùng và DB Testcontainers phải tách biệt.

## Phase 1 — Authentication và phiên đăng nhập

**Đã có:** register FARMER/TRADER; verify/resend OTP; login JWT RS256; refresh rotation; logout đúng owner; quota OTP và login rate limit trên DB; seed Admin idempotent, UNVERIFIED trước OTP; cleanup; script tạo key/OTP secret local và Mailpit.

**Việc vận hành cần hoàn tất trước demo/tích hợp:**

- Điền hai bộ ADMIN_1_*/ADMIN_2_* độc lập, bật seed và verify từng email. Không dùng fixture làm identity thật.
- Cấu hình SMTP/sender thật khi triển khai ngoài local; giữ private key/OTP secret ổn định qua restart.
- Thực hiện rotation với current/previous public key, overlap ít nhất 16 phút; kiểm tra timeout và secret injection của môi trường đích.

**Điều kiện nghiệm thu:** luồng register → verify → login → refresh → logout chạy được; sai role/OTP/credential, quota, refresh đồng thời, refresh-vs-lock, JWT sai claims và DB state unavailable đều có test. Các kiểm tra liên service còn được thực hiện lại ở Phase 7.

## Phase 2 — Private/public profile

**Đã hoàn thành 19/10/2026:** năm endpoint, DTO riêng tư/công khai, PUT theo role và outbox transactional; 19 test profile đạt. Xem README và implementation-decisions để biết semantics và giới hạn avatar/relay.

**Phụ thuộc:** Phase 1.

**Công việc:**

- GET/PUT `/api/v1/users/me`; private DTO theo role, ADMIN profile=null, PUT profile ADMIN trả400.
- GET `/api/v1/users/{accountId}`, `/api/v1/farmers/{farmerId}`, `/api/v1/traders/{traderId}`.
- Caller phải ACTIVE; target phải là Farmer/Trader ACTIVE và đúng role. Target ADMIN/sai role/không ACTIVE trả404.
- Public whitelist đúng sáu field: accountId,displayName,avatarUrl,province,district,joinedAt. Không lộ email/phone/address/buyingArea/reputation.
- PUT giữ semantics đầy đủ theo BA: tên required, optional vắng trở thành null; không tự đổi sang PATCH, không sửa role/email/password/status/reputation.
- Chuẩn bị lớp ghi outbox/envelope dùng chung ở mức tối thiểu: cập nhật profile, tăng profileVersion và lưu USER_PROFILE_UPDATED cùng transaction. Relay Kafka chưa chạy ở phase này.
- Chỉ chấp nhận avatar reference thuộc media owner hợp lệ; khi upload chưa có, không cho client tự gán URL ngoài.

**Đầu ra:** profile DTO/service/repository/controller, fixture request/response và test ownership/whitelist/validation/transaction rollback/version.

**Điều kiện nghiệm thu:** người dùng đọc/sửa đúng hồ sơ; dữ liệu private không xuất hiện trong public response/outbox; lỗi DB không để lại profile update một phần. Đồng bộ sang Trading chỉ được coi hoàn tất sau Phase 5.

## Phase 3 — Admin quản lý account và trạng thái

**Đã hoàn thành 02/11/2026:** đủ sáu endpoint Admin; DTO theo BA, PATCH allowlist, phân trang/filter/sort, soft INACTIVE, revoke refresh và transactional account/profile/email outbox. Khóa advisory transaction chung được lấy trước account row lock; kiểm lại Admin ACTIVE trong transaction, bảo vệ cả thao tác đồng thời. Auth tắt thì không khởi tạo module Admin. Chi tiết quyết định và kết quả kiểm chứng tại `implementation-decisions.md`.

**Phụ thuộc:** Phase 1–2; hạ tầng ghi outbox cùng transaction từ Phase 2.

**Công việc:**

- POST/GET `/api/v1/admin/accounts`; GET/PATCH/DELETE `/api/v1/admin/accounts/{accountId}`; PATCH `/api/v1/admin/accounts/{accountId}/status`.
- Admin chỉ tạo FARMER/TRADER UNVERIFIED, tái sử dụng validation/OTP và bảo đảm SMTP sau commit. Không có API tạo ADMIN.
- Profile PATCH dùng allowlist; không sửa role/email/password/status/reputation tại endpoint profile.
- List page=0,size=20,max100, filter role/status, sort createdAt DESC/accountId DESC; DTO phân trang theo Contract v2.1.
- DELETE là soft INACTIVE. LOCKED/INACTIVE thu hồi tất cả refresh cùng transaction; phân biệt admin_lock/reputation_lock, restore không bypass verified/score/lock khác. ADMIN không áp score>0.
- Chặn self-lock/self-disable/self-delete và vô hiệu Admin ACTIVE cuối cùng. Dùng cơ chế khóa chung phù hợp để bảo vệ cả hai Admin đồng thời vô hiệu người còn lại; không chỉ COUNT ngoài transaction.
- Tăng version phù hợp, ghi event account/profile visibility và ý định gửi mail khóa/mở cùng transaction. Audit bằng log bảo mật, không thêm bảng audit Admin.

**Đầu ra:** API Admin, policy chuyển trạng thái, fixtures, test quyền/allowlist/pagination, race giữa Admin và lock-vs-refresh/login.

**Điều kiện nghiệm thu:** không thể vô hiệu Admin ACTIVE cuối cùng; refresh bị revoke sau lock; JWT cũ bị chặn trên request mới; restore không tự reset điểm hoặc xóa nguyên nhân khóa khác. Mail/Kafka delivery chỉ nghiệm thu sau Phase 5.

## Phase 4 — Avatar và lưu trữ ảnh

**Đã hoàn thành 02/11/2026:** hai endpoint, JPEG/PNG/WebP decode/re-encode, giới hạn5MiB/20MP, UUID storage bền vững, metadata/profile/outbox atomic, owner/public access và cleanup orphan24h. ADMIN đã được người dùng đồng ý lưu media_assets/resource_id=accountId, trả avatarUrl qua upload, owner-only read và GET me vẫn profile=null. Có10 unit và15 PostgreSQL integration test; cấu hình volume/Docker đã chuẩn bị, nghiệm thu image/restart deployment/backup restore thuộc Phase7.

**Phụ thuộc:** Phase 2–3; metadata/outbox ghi nhất quán.

**Công việc:**

- POST `/api/v1/users/me/avatar`, GET `/api/v1/users/media/{mediaId}` theo BA.
- Farmer/Trader: JPEG/PNG/WebP, tối đa5 MiB/20MP, kiểm nội dung thực, decode/re-encode; UUID storage key, persistent volume riêng ngoài webroot.
- Không fetch URL ngoài hoặc tin filename/path/MIME client. Download kiểm owner/quyền public profile ACTIVE, trả private,no-store và nosniff; không tiết lộ đường dẫn ổ đĩa.
- File phải bền vững trước commit metadata/profile/outbox. Lỗi DB tạo orphan được cleanup sau24h; không báo thành công khi file chưa lưu xong, không xóa ảnh đã attach cùng job orphan.
- Nhánh ADMIN đã chốt: metadata trong media_assets, resource_id=accountId; upload trả avatarUrl, chỉ owner đọc. GET me profile=null, không public event, không tạo profile Farmer/Trader giả.

**Đầu ra:** storage adapter, validator, API, metadata/cleanup và test MIME giả/size/pixel/path/ownership/restart/orphan.

**Điều kiện nghiệm thu:** Farmer/Trader upload/read đúng quyền, dữ liệu không mất sau restart, file và DB nhất quán. Đã kiểm bằng JAR thật qua HTTP và restart JVM với cùng DB/media directory. Nhánh ADMIN có quyết định persistence/DTO và test owner-only. Kiểm chứng image/backup trên môi trường bàn giao thuộc Phase7.

## Phase 5 — Outbox, Kafka và đồng bộ profile

**Đã hoàn thành trong phạm vi User/local 02/11/2026:** relay chờ broker ACK, Kafka/email worker độc lập, retry giữ eventId/payload, verify→profile event atomic, bootstrap snapshot/tombstone và V3 sequence/index cho delivery order. Đã test broker down/recovery, post-ACK/SMTP crash, duplicate, multi-worker, version/visibility và email verified/support. Delivery mặc định tắt; bật OUTBOX_KAFKA_ENABLED/OUTBOX_EMAIL_ENABLED sau khi có môi trường đích, bootstrap opt-in riêng. Không coi fixture là Trading/Notification thật đã tích hợp.

**Phụ thuộc:** Phase 2–4 đã ghi outbox cùng transaction; có Kafka test/local.

**Công việc:**

- Relay publish rồi đánh dấu sau broker xác nhận; retry giữ eventId/payload. Chấp nhận at-least-once, không claim exactly-once DB/Kafka.
- USER_PROFILE_UPDATED trên `soa.user.profile.v1`, key accountId, đúng envelope/whitelist/profileVersion. Khi ẩn gửi publicVisible=false,profile=null.
- Hoàn thiện mọi trigger: verify email thành ACTIVE, sửa profile/avatar, Admin thay đổi visibility/status. ADMIN không phát public Farmer/Trader profile.
- Job bootstrap phát snapshot hiện hành để Trading dựng replica; không thêm public bootstrap API hoặc cho Trading đọc user_db.
- Phát ACCOUNT_LOCKED/ACCOUNT_UNLOCKED theo Contract, accountVersion/recipient/reference đúng. Mail khóa/mở dùng channel outbox riêng, retry độc lập; SMTP lỗi không rollback account.
- Không đưa email/OTP/token/password hoặc contacts riêng tư vào business Kafka payload.

**Đầu ra:** relay/producer/bootstrap/email worker, Kafka integration fixtures, cấu hình local và test broker down/retry/crash/duplicate/version/visibility.

**Điều kiện nghiệm thu:** cập nhật business và outbox atomic; crash sau publish không mất event; replay không làm profile cũ hiện lại ở consumer fixture. Tích hợp Trading/Notification thật cần kiểm chứng với owner tương ứng ở Phase 7.

## Phase 6 — Reputation consumer và kết quả xử lý

**Đã hoàn thành và kiểm chứng phía User ngày02/11/2026:** PENALTY/COMPLETION_REWARD/REVERSAL, cap/history/dedup/lock/outbox, commit-before-ACK, retry/DLT và replay. Payment xác nhận reversal trỏ trực tiếp gốc phạt/thưởng; User đảo actualDelta đã lưu rồi clamp, mỗi gốc một lần, không đảo reversal/tự phục hồi thưởng/đổi tiền cọc. Reversal score>0 bỏ reputation_lock nhưng giữ status/admin_lock/INACTIVE/verified, chờ Admin mở ACTIVE. User chấp thuận chuẩn hóa base version1/mọi reversal version2; **Payment phải sửa producer reversal thưởng version1 và bàn giao fixture mới trước khi bật listener**. Listener mặc định tắt; kiểm chứng Payment thực tế thuộc Phase7. Xem reputation-contract-and-recovery.md.
**Phụ thuộc:** Phase 3 và Phase 5; thống nhất các invariant decision với owner Payment trước tích hợp.

**Công việc:**

- Consume CONTRACT_REPUTATION_DECIDED từ payment-service, topic `soa.payment.reputation-decision.v1`, key accountId, group `user-reputation-decision-v1`.
- Validate envelope/source/schema/action/UUID/role/mã vi phạm/mức điểm theo Contract v2.1 và D01–D07. User không tự kết luận ai vi phạm, không tự tạo job xác nhận Contract hoặc thưởng72h của Payment.
- PENALTY, COMPLETION_REWARD, REVERSAL: clamp0..10/bước0,5, history requestedDelta/actualDelta/before/after, thưởng+0,5 và cap1 điểm/cặp/30 ngày, unique business keys; cap hit ghi actualDelta0, không cộng lại sau.
- Dedup cả khi eventId mới nhưng decision business key cũ; cùng key khác dữ liệu phải phát hiện và xử lý, không silently accept.
- Reversal đảo actualDelta gốc một lần; thiếu gốc retry/DLT, không ACK bỏ qua. Kiểm tra thứ tự reward/reversal/penalty và liên kết version/gốc.
- Khóa account khi score0, revoke refresh, phân biệt admin_lock/reputation_lock/INACTIVE/verified; không tự reset điểm. Cách mở đã chốt: reversal hợp lệ đưa score>0 chỉ bỏ reputation_lock, chờ Admin mở ACTIVE; đã có kiểm thử cả Admin mở thủ công và JWT trong thời gian chờ.
- Ghi score/history/flags/revoke/outbox cùng transaction, commit trước offset; phát REPUTATION_CHANGED và account events đúng payload. Mail dùng worker của Phase 5.
- Xác định retry/DLT riêng User trong ADR; không tự nhận lịch Notification làm policy User. Forward thành công trước khi commit offset; replay có kiểm soát.

**Đầu ra:** consumer/domain policy/history/dedup, ADR invariant và retry, fixtures Payment/User/Notification, test replay/cap/reversal/order/partial rollback/zero lock/crash.

**Điều kiện nghiệm thu:** một quyết định chỉ tác động một lần; reversal không đảo hai lần; lỗi giữa transaction không để lại score/history/lock lệch nhau; không mất record khi retry/forward lỗi.

## Phase 7 — Tích hợp, Docker và bàn giao

**Phụ thuộc:** Phase 2–6 đạt nghiệm thu; các nhánh mâu thuẫn BA đã có quyết định được ghi lại.

**Công việc:**

- Chạy `mvnw.cmd verify` đầy đủ và smoke test trên môi trường biệt lập; không dùng user_db thật cho test có TRUNCATE.
- Build/run image Docker của User; xác nhận profile/secrets, health/readiness, migration/restart, volume ảnh, backup/restore DB + media.
- Với owner khác: Gateway chặn public internal path; key riêng caller; timeout tổng1s; old JWT sau lock bị chặn ở Gateway/backend/WebSocket. Không coi User đơn lẻ đã chứng minh toàn hệ thống.
- Kiểm chứng User→Trading replica, Payment→User decision và User→Notification; sử dụng fixtures/schema contract đã thống nhất. Không tự sửa repo khác ngoài phạm vi được giao.
- Kiểm tra SMTP thật và hai Admin đã verify trên môi trường demo; cấu hình domain/origin, SUPPORT_EMAIL, key rotation và hướng dẫn DLT/recovery.
- Hoàn thiện API examples, README, .env.example không secret, implementation-decisions và ma trận requirement → code → test → kết quả.

**Điều kiện hoàn thành toàn User Service:** đủ20 endpoint theo BA, đầy đủ producer/consumer/job trong phạm vi User, các policy/concurrency test đạt, không còn nhánh bắt buộc bị đánh dấu blocked, và có bằng chứng chạy trên môi trường bàn giao. Không tự mở rộng whitelist/API hoặc thay contract producer để làm test qua.

## Những điểm cần quyết định đúng thời điểm

| Điểm còn thiếu/mâu thuẫn | Cần chốt trước | Có thể tiếp tục độc lập |
| --- | --- | --- |
| Payment sửa reversal thưởng thành version2 và bàn giao fixture producer mới | Bật listener và tích hợp Payment ở Phase7 | Code/test Phase6 phía User |
| Đối chiếu binding envelope/key/order, ACL/retention và recovery với owner thật | Bật consumer production ở Phase7 | Kiểm thử User bằng fixtures/Testcontainers |

Không mở lại D01–D07 đã chốt; chỉ làm rõ các invariant triển khai còn thiếu, ghi quyết định trong implementation-decisions/ADR và đồng bộ với owner khi liên quan giao tiếp.

## Cấu hình cần cung cấp theo phase

- Phase 1/vận hành: SMTP/sender, OTP secret, RSA keys/kid, hai Admin riêng và SUPPORT_EMAIL trước demo.
- Phase 4: MEDIA_STORAGE_DIR, persistent volume và cách backup cùng DB.
- Phase 5–6: Kafka bootstrap/security/topic configuration, service keys, fixtures và invariant của các owner.
- Phase 7: origin/domain thật, deployment secrets, public key set/rotation và xác nhận tích hợp từ các owner.

**Bước triển khai tiếp theo:** Phase7: nhận fixture/producer Payment mới cho reversal thưởng version2, đối chiếu Payment→User→Notification cùng thứ tự/recovery, rồi mới bật listener theo môi trường. Tiếp tục nghiệm thu Docker/deployment/backup/SMTP và liên service theo checklist Phase7.
