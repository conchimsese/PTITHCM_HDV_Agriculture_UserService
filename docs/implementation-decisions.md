# Quyết định triển khai giai đoạn nền

## Phase 3 — Admin account và chuyển trạng thái — 02/11/2026

- Hoàn thiện sáu endpoint theo BA User §6–7. Dùng lại register validation/OTP; tạo FARMER/TRADER UNVERIFIED, cấm ADMIN. Transaction kiểm lại caller ADMIN ACTIVE trước insert; account/profile/OTP commit trước SMTP. Không thêm API/role/migration hoặc bảng audit.
- DTO AccountSummary đúng sáu field BA; detail bổ sung emailVerifiedAt/profile, ADMIN profile=null. Không mở rộng response với reputation/flags/versions nội bộ. List đọc REPEATABLE_READ, filter role/status, sort createdAt DESC/accountId DESC; page mặc định0,size20,max100.
- PATCH chỉ allowlist theo role, giữ field vắng và cho optional=null. Thay đổi public snapshot tăng profile_version; khi target ẩn ghi tombstone publicVisible=false/profile=null. Đổi riêng contacts không phát public event. Avatar vẫn chỉ reference metadata attached của owner, storage/API thuộc Phase 4.
- Tất cả Admin mutation dùng `pg_advisory_xact_lock(hashtext('user-service-admin-mutations'))` trước bất kỳ account row lock, rồi khóa/kiểm caller ACTIVE và khóa target. Count Admin ACTIVE nằm trong transaction đã serialize. Không khóa target rồi mới khóa tất cả Admin, tránh deadlock hai Admin vô hiệu nhau. Khóa chung có phạm vi PostgreSQL transaction, dùng được giữa nhiều instance; đánh đổi serialize Admin writes ở MVP. Auth login/refresh vẫn khóa account trước refresh, không lấy advisory lock nên không tạo thứ tự ngược.
- Cấm self-lock/self-disable/self-delete; request mutation đang chờ nhưng actor đã bị vô hiệu trả409 INVALID_STATE_TRANSITION. Filter vẫn chặn JWT cũ ở request mới bằng403 ACCOUNT_NOT_ACTIVE. LOCKED/INACTIVE revoke refresh atomic. Restore không bypass verified/reputation_lock/score; ADMIN không áp score. Admin khóa thêm khi account đã reputation-lock ghi admin_lock riêng, không làm mất nguyên nhân khóa hoặc tự reset điểm.
- Thay đổi status tăng account_version; đổi visibility tăng profile_version, ghi profile tombstone/snapshot. ACCOUNT_LOCKED/UNLOCKED envelope đúng Contract v2.1 §9, data chỉ accountId/reasonCode/accountVersion, recipient/reference là accountId. reasonCode ADMIN_LOCK/ADMIN_UNLOCK là mã kỹ thuật cho thao tác quản trị; reason tự do nằm riêng channel EMAIL. Không đưa contacts hoặc lời giải thích tùy ý vào Kafka; audit log chỉ actor/target/action.
- Ghi KAFKA account + EMAIL intent + profile event trong cùng transaction. Không có relay/email worker ở Phase 3; SMTP lỗi khi delivery khóa/mở sau này không rollback business. Nhánh profile public bị thiếu khi restore fail503 và rollback thay vì phát snapshot null cho account ACTIVE.
- Module Admin controller/service dùng cùng điều kiện `user.auth.enabled` với AuthService, khắc phục FoundationPostgresIT không khởi động khi auth=false.

Verification: Maven 3.9.11/JDK21, `mvnw.cmd -B -o -Dmaven.repo.local=<workspace>/.m2 -Ddebug=false clean verify` (thực thi bằng Maven đã bootstrap trong `.tools`) đạt **BUILD SUCCESS, 92 test**: 25 unit/Web/security và 67 integration, 0 failure/error/skipped. PostgreSQL17 và Mailpit chạy Testcontainers biệt lập; không chạy TRUNCATE trên DB người dùng. Admin gồm 5 Web và 18 PostgreSQL test. Log local `.tools/phase3-verify.log`; report tại target/surefire-reports và target/failsafe-reports. Không build/run image ứng dụng hoặc claim tích hợp liên service/SMTP production trong Phase 3.

| Requirement Phase 3 | Code | Test và kết quả |
| --- | --- | --- |
| ADMIN ACTIVE; create FARMER/TRADER UNVERIFIED, OTP sau commit | AdminAccountController/Service, AuthService.registerByAdmin | accessControl, createAccount, adminCreateCommitsBeforeSmtp: đạt |
| List/filter/page/sort, DTO BA, PATCH allowlist | AdminAccountRepository, AdminDtos, mergePatch | listAccounts, accountDtosAndPagination, patchProfile: đạt |
| Self/last Admin, concurrent mutations | lockAndCheckAdmin, advisory lock trước account | selfAction_and_lastAdminProtection, concurrentAdmins: đạt, một200/một409 và còn một ACTIVE |
| Restore verified/score/flags, ADMIN score=null | updateStatus | restoreInvariants, reputationLockAndAdminLock: đạt |
| Revoke refresh, JWT cũ, lock-vs-refresh/login | updateStatus/deleteAccount, AuthService, CurrentAccountFilter | updateStatus_lockRevokesTokens, adminLockRacingRefreshOrLogin, softDelete: đạt |
| Atomic status/profile/outbox, đúng payload/visibility | AccountOutboxWriter, ProfileOutboxWriter | emailOutboxFailure, profileOutboxFailure, accountOutboxHasContractPayload, hiddenProfilePatch: đạt |
| Không hồi quy auth=false | Điều kiện module Admin | 8 FoundationPostgresIT và toàn bộ auth/profile regression: đạt |

## Giai đoạn authentication — 18/10/2026

Chỉ đạo mới nhất triển khai giai đoạn tiếp theo của kế hoạch: auth/OTP/SMTP/JWT/refresh/logout/rate limits/seed. Không triển khai profile/Admin CRUD, avatar, outbox relay hoặc reputation consumer trong lượt này. Không commit/push.

- Giữ V1 nguyên vẹn; V2 thêm login_attempt_windows, khóa bucket IP+username để chống race và chia sẻ giới hạn giữa các instance. Không Redis. Remote address từ container request; forward-headers-strategy=none, không tin X-Forwarded-For chưa được cấu hình trust.
- Auth dùng JDBC + TransactionTemplate trên transaction manager cùng datasource. Thứ tự lock account trước OTP/refresh; login lock rate bucket trước account, không có nhánh khóa ngược. Refresh rotation và kiểm ACTIVE cùng transaction, chỉ một request refresh thắng. Logout kiểm ownership và ACTIVE lại sau lock, không dựa riêng state đã kiểm ở filter.
- OTP dùng SecureRandom, HMAC-SHA-256 liên kết accountId/otpId/email/code; chỉ hash lưu DB. Mã mới tránh trùng giá trị mã gần nhất. Failed attempt phải commit nên verify/login trả lỗi ra ngoài transaction sau khi commit counters; không throw khiến rollback counters. Register commit trước SMTP, response201 SENT/FAILED; resend commit OTP/quota trước SMTP,503 nếu cần gửi nhưng lỗi. OTP không đưa vào outbox/Kafka/log.
- Cleanup hằng ngày 03:00 UTC: xóa OTP used/expired, giữ issuance ít nhất hai ngày, không làm mất rolling quota một giờ. Query quota và issuance insert được bảo vệ bằng account row lock; email/username bất biến ở giai đoạn này.
- JWT ký RS256/900s, sub UUID, aud soa-api, iss URN, role enum đơn, iat/exp/jti/kid. Nimbus dùng local key selector, không discovery/JWKS HTTP; decode chỉ RS256, key hiện hành + public key trước. Claim validation bắt buộc trường/UUID/lifetime<=900/iat không tương lai quá60s; skew<=60s. Deployment giữ previous public key>=16 phút sau lần ký cuối bằng key cũ. Tham chiếu [Spring Security 6.5 JWT](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html).
- Protected User request có Bearer xác minh tiếp current state trực tiếp DB, so role hiện tại, không cache ACTIVE. Internal vẫn chỉ dùng service key, không dùng token của caller thay thế. Các public auth endpoint và health không bắt kiểm state ACTIVE. Endpoint chưa triển khai vẫn deny by default.
- DTO mang password/OTP/token/config secret override toString bằng nội dung redacted; SMTP debug tắt. Không log mail body/token/private key hoặc serialize exception SQL cho client. Refresh DB chỉ SHA-256,7 ngày/token; revoke cũ+insert mới+ký JWT cùng transaction. Logout idempotent với token thuộc account, không claim revoke access JWT còn hạn.
- Script GenerateLocalAuth.java tạo RSA3072/OTP secret chỉ cho local, giữ giá trị .env đã điền và cặp key có sẵn. Production thiếu secrets phải fail startup, không sinh key giả/fallback mỗi lần restart. Mailpit là mail sink local ([release v1.27.10](https://github.com/axllent/mailpit/releases/tag/v1.27.10)), không cấu hình SMTP giả cho production.
- Seed có opt-in để không tạo identity giả: bật ADMIN_SEED_ENABLED với ít nhất hai username/email/password độc lập trước integration/demo. Validate toàn bộ danh sách trước khi seed, mỗi seed transaction có advisory lock để idempotent giữa instance. Existing identity chỉ được giữ nếu username/email/role khớp; không reset password/status, không bypass OTP. Profile ADMIN=null, reputation null; không API tạo ADMIN. SMTP lỗi không rollback seed; gọi resend theo policy.
- Verify email chuyển ACTIVE chưa phát USER_PROFILE_UPDATED: producer/relay/bootstrap thuộc giai đoạn outbox sau, chưa claim Trading replica đã được đồng bộ.

Verification giai đoạn auth: `mvnw.cmd -B -Dmaven.repo.local=<workspace>/.m2 -Ddebug=false verify` đạt **BUILD SUCCESS**, tổng **50 test** (20 unit/Web/security và 30 integration), 0 failure/error/skipped. PostgreSQL17 và Mailpit Testcontainers thật; gồm 21 test auth trên PostgreSQL, 1 SMTP delivery/failure, 8 foundation. Các tình huống concurrency, quota qua cleanup, refresh-vs-lock, seed hai Admin idempotent, JWT không hợp lệ/previous key và DB state unavailable với JWT hợp lệ đều đạt. Report ở target/surefire-reports và target/failsafe-reports; log local `.tools/auth-verify.log`.

Đã chạy JAR thật với `.env`/profile local ở port8081: DB Windows kết nối thành công, Flyway áp V2 trên schema V1 hiện có, health UP, public register ADMIN400 không tạo account. Phiên JAR kiểm tra đã dừng. Đã tạo key/OTP secret local bằng script, giữ mật khẩu DB và các giá trị nonempty đã có; Mailpit local được bật qua `docker compose up -d user-mail` (UI localhost:8025). Chưa bật seed trên DB người dùng vì chưa có hai identity/password/email thật được cấu hình. Production SMTP/rotation và tích hợp với repo khác chưa được kiểm chứng; Dockerfile ứng dụng chưa build image trong lượt này.

Ngày: 08/10/2026. Chỉ sửa repo User; không commit/push. Yêu cầu mới nhất giới hạn bootstrap, DB/migration, errors, internal access-state, local config và kiểm thử.

## Nguồn và phiên bản

- Đã đọc văn bản/bảng từ `BA - SOA.docx`: User §1–19 và Contract chung MVP v2.1 §1–19, D01–D07, Change Log. Không suy nội dung riêng trong ảnh/sơ đồ nhúng.
- Ưu tiên: chỉ đạo mới nhất → Conflict đã chốt → Contract v2.1 → rule → spec → prompt triển khai.
- Repo ban đầu chỉ có hai tài liệu. Java 21, Boot 3.5.16 và Maven 3.9.11. Boot patch được xác nhận trong tài liệu chính thức và Maven Central; dependency runtime/test theo BOM, không pin riêng.
- Tài liệu chính thức: [system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [managed dependencies](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html), [Maven 3.9.11](https://maven.apache.org/docs/3.9.11/release-notes.html).

## Đối chiếu schema BA cũ/v2.1

| Nội dung cũ | Baseline áp dụng | Thực hiện |
| --- | --- | --- |
| User §4 nói outbox cần duyệt; các mục cũ nhấn mạnh 5 bảng | User §5.7, Contract §10/§18 yêu cầu history/outbox/media | Migration tạo 5 bảng nền + 3 bảng v2.1 |
| Khóa Admin và reputation có thể bị gộp | Contract §10 tách admin_lock/reputation_lock | Hai flag độc lập, ACTIVE chỉ khi không có lock và đã verify |
| Public profile ví dụ cũ/điểm chưa duyệt | Contract §4/§9 public whitelist | Access-state chỉ 4 field; chưa có public profile API |
| Mở khóa chỉ cần email verified | User §8.2/Contract §12: Farmer/Trader score>0 và không còn lock khác | CHECK ACTIVE đúng điều kiện; ADMIN score null |

`otp_issuances` là bảng kỹ thuật lưu timestamp phát OTP riêng với OTP có thể cleanup, đáp ứng Contract §19. FK account và index email/time; không xóa cascade khi cleanup OTP. Giai đoạn auth sau sẽ khóa row account, ghi OTP + issuance cùng transaction và kiểm quota; bảng này chưa chứng minh quota API đã được triển khai.

Profile có cột kỹ thuật `account_role` cố định, composite FK `(profile_id,account_role)` → `(accounts.account_id,role)` để DB chặn tạo sai profile theo role; không thêm role mới. Transaction register giai đoạn sau phải bảo đảm tạo đúng profile. DB chưa ép account FARMER/TRADER luôn có profile vì cần insert account rồi profile trong cùng transaction.

Score Farmer/Trader phải được ghi là 10 khi register (chưa có register ở giai đoạn này); ADMIN null. CHECK 0..10, bước 0,5, score0 yêu cầu reputation_lock; ACTIVE yêu cầu verified và các flag cho phép. Mọi FK nằm user_db; counterparty/contract/decision UUID chỉ là logical reference. Index reward/reversal chống trùng là schema nền, chưa có consumer/reward policy.

OTP/refresh lưu hash hex64 (HMAC-SHA-256/SHA-256), thêm invalidated_at để phân biệt vô hiệu mã cũ với used_at chỉ verify thành công. Khi phát mã mới cần invalidation mã hết hạn/cũ trước insert vì partial unique index chỉ cho một mã chưa dùng/chưa invalidated.

Outbox có channel KAFKA/EMAIL, retry metadata và index bản ghi chưa publish. Đây là schema phục hồi cho relay sau; không publish-after-save, chưa claim delivery semantics đã hoạt động. Business Kafka payload sau không được có email/OTP/token; payload email nằm riêng channel, không đưa ra Kafka. `media_assets` có metadata/UUID storage key và giới hạn MIME/size; chưa có filesystem adapter hoặc avatar API.

## Access-state và security

- Endpoint contract duy nhất: GET `/internal/v1/accounts/{accountId}/access-state`. Credential riêng từng caller qua `X-Service-Key`, không có role SERVICE trong accounts. Allowlist cố định endpoint/method này cho mỗi caller có key cấu hình; blank vô hiệu caller, key ngắn/trùng bị từ chối startup.
- Key so sánh constant-time; không lưu credentials trong Authentication, không tạo session, không tin identity header từ client. Service key không authorizes public API.
- Repository JDBC truy vấn DB primary mỗi request; không HTTP tự gọi User, không cache ACTIVE. Dùng JDBC để chỉ chọn account_id/status/role, không expose entity; JPA đã cấu hình `validate` cho entity của các giai đoạn sau.
- 200 với status hiện tại và boolean; caller nội bộ phải từ chối request người dùng khi boolean false, timeout hoặc User lỗi. 404 account absent, 503 DB unavailable, no-store cả success và error. Tổng timeout 1 giây do caller đặt; timeout query/pool của server không thay thế timeout tổng caller.
- Public API deny by default; chưa claim JWT validation/cấp token đã hoàn tất. Actuator chỉ expose health, show-details never. Không cấu hình discovery cho issuer URN; JWT wiring thuộc giai đoạn auth sau.
- CORS public chưa mở. Compose bind localhost; deployment private networking và chặn route internal thuộc Gateway/deployment owner, không sửa repo khác.

## Phạm vi chưa làm

Auth/OTP SMTP/seed Admin, rate limits, refresh rotation, profile/Admin CRUD, media và outbox relay còn ở giai đoạn sau. Reputation consumer, quy tắc sửa quyết định/version/retry riêng User và avatar Admin chưa triển khai theo chỉ đạo hiện tại. Chưa cần quyết định các nhánh đó để hoàn thành giai đoạn nền.

## Verification thực tế

- `mvnw.cmd -version`: Maven 3.9.11, JDK Temurin 21.0.12.1. Wrapper tải ZIP với SHA-256 đã pin; ZIP bootstrap cũng đối chiếu SHA-512 từ Maven Central.
- `mvnw.cmd -B -Dmaven.repo.local=<workspace>/.m2 -Ddebug=false verify`: **BUILD SUCCESS**, 15 test Web/security + 8 integration test PostgreSQL 17.11 qua Testcontainers; tổng 23, không failure/error/skipped. Flyway validate/migrate/re-run, UNIQUE/role/profile/score, row lock, quota retention, state sau commit và health đều đạt. Log local ở `.tools/verify.log`, report ở `target/`.
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Smoke-Local.ps1`: **SMOKE PASS**, JAR thật port 8081 với profile local; health UP, thiếu key401, account404, ACTIVE200, LOCKED sau commit200/accessAllowed=false, no-store, DB thật dừng→503 ACCESS_STATE_UNAVAILABLE/readiness DOWN. JVM và Compose project/volume test đã được dọn.
- Docker Desktop lúc đầu chưa chạy; đã khởi động để chạy PostgreSQL thật. Không có test bị bỏ qua vì Docker/network. Dockerfile đã tạo, chưa build/run image ứng dụng; smoke chạy JAR trên host và PostgreSQL trong Compose.
- Môi trường local bình thường cần điền DB_PASSWORD và key riêng cho caller muốn bật trong `.env`. Không tạo credential production, không seed Admin trong lượt này. Không commit/push.

## Phase 2 — Profile và transactional outbox — 19/10/2026

- GET/PUT /api/v1/users/me lấy identity duy nhất từ JWT sub. Ba public endpoint chỉ trả Farmer/Trader ACTIVE theo đúng role; whitelist đúng sáu field, không đọc password hash. ADMIN đọc profile=null và PUT trả400.
- BA không đưa ví dụ envelope riêng cho PUT profile: chọn object profile trực tiếp theo role, được ghi trong README/fixtures. Tên required, optional vắng trở thành null; field lạ hoặc sai role bị từ chối kể cả null. Không biến PUT thành PATCH.
- Private GET dùng transaction read-only REPEATABLE_READ để account/profile nhất quán. PUT khóa account trước rồi đọc profile mới, kiểm ACTIVE bên trong transaction. Public GET dùng một SQL statement và chỉ chọn whitelist.
- Thay đổi public snapshot mới tăng profile_version và ghi USER_PROFILE_UPDATED cùng transaction. PUT giống dữ liệu hiện tại không ghi lại; đổi riêng phone/address/buyingArea không tạo public event/version. account_version không đổi. Lỗi outbox rollback cả profile và version. Không sửa V1/V2 hoặc thêm migration vì schema hiện có đủ dùng.
- Writer chỉ chạy trong business transaction, lưu envelope vào JSONB: sourceService user-service, eventVersion1, topic soa.user.profile.v1, aggregate_id accountId, recipientIds=[] và profile snapshot năm field public. Chưa phát event sang Trading. Relay, event ban đầu khi verify, status/avatar và bootstrap thuộc Phase 5.
- avatarUrl chỉ là reference tương đối chuẩn đến metadata đã attached thuộc cùng owner/resource. Phase 2 không có lưu file/upload/download; URL ngoài, media tạm và owner khác bị từ chối.
- Chuyển UTC Clock sang cấu hình dùng chung, để profile vẫn có Clock trong context nền tắt auth.
- Smoke bản chạy local qua spring-boot:run port8081 đạt health UP, GET me/PUT me/public/Farmer 200, PUT lặp 200, sai role404; public response đúng sáu field. Chỉ cập nhật account Farmer demo đã tạo trước đó và logout token kiểm tra. Native PostgreSQL local là 18.6; bộ integration dùng PostgreSQL17. Flyway validate hai migration thành công trên local nhưng có cảnh báo phiên bản18 mới hơn mức17 đã được Flyway kiểm chứng.
- clean verify đạt 69 test: 20 unit/Web/security + 49 integration, gồm 19 test profile trên PostgreSQL Testcontainers. Kiểm ownership, whitelist response/event, validation, PUT replacement, lỗi outbox rollback, cập nhật đồng thời khác nhau và trùng nhau; không failure/error/skipped. Log local .tools/profile-verify.log. Windows cần dừng process chạy JAR trước repackage để tránh khóa file.

## Sửa cấu hình kết nối local — 12/10/2026

- PostgreSQL Windows đang phục vụ localhost:5432. Kiểm tra trực tiếp xác nhận database `user_db` tồn tại, role `user_db` không tồn tại; mật khẩu người dùng cấu hình đăng nhập thành công với role `postgres`.
- Tạo `.env` local bị Git ignore với DB_USERNAME=postgres và credential hiện có. Khôi phục datasource/port trong application.yml về biến cấu hình; xóa credential khỏi `.env.example`, sửa DB_PORT mẫu thành 5432. Không tạo/đổi role, password hoặc xóa dữ liệu PostgreSQL.
- `mvnw.cmd package`: BUILD SUCCESS, 15 test Web/security đạt. Chạy JAR với profile local trên port8081: Flyway validate thành công, schema v1 đã cập nhật nên không chạy migration mới, `/actuator/health` trả UP. Đã dừng phiên JAR kiểm tra; chạy lại theo README khi cần. Không chạy lại Testcontainers IT trong lần sửa cấu hình này.
