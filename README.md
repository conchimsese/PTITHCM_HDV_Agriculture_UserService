# User Service — authentication, profile, Admin, avatar và outbox

Java 21, Spring Boot 3.5.16, Maven Wrapper 3.9.11, PostgreSQL 17, Flyway; port mặc định **8081**. Nguồn yêu cầu: `docs/BA - SOA.docx` (User Service và Contract chung MVP v2.1) và `docs/Prompt_User_Service.md`.

## Phần đã triển khai

- Project và dependency theo Spring Boot BOM: Web, Validation, Security/OAuth2 Resource Server, JPA, PostgreSQL, Flyway, Mail, Kafka, Actuator, test.
- Migration `V1__user_foundation.sql`: năm bảng nền, `reputation_history`, `outbox`, `media_assets` và `otp_issuances` cho quota độc lập cleanup. UUID, TIMESTAMPTZ, FK nội bộ user_db, ràng buộc role/status/score/profile và index.
- `GET /internal/v1/accounts/{accountId}/access-state`: đọc DB primary trực tiếp mỗi lần, không cache, service key riêng từng caller, endpoint allowlist, response/error `Cache-Control: no-store`.
- Lỗi JSON `{timestamp,status,code,message,path}`; không trả SQL, stack trace hoặc secret. Health chỉ trả trạng thái tổng quát.
- Cấu hình local, Dockerfile và Compose riêng User.
- Sáu API auth: register, verify-email, resend-verification, login, refresh, logout; OTP HMAC-SHA-256, SMTP sau commit, BCrypt, RS256, refresh rotation, rate limit DB và seed Admin có OTP.
- Migration V2 bổ sung cửa sổ thử sai đăng nhập theo IP thực + username chuẩn hóa. Protected User request có JWT kiểm current state trực tiếp ở DB.

- Phase 2: private/public profile, PUT toàn bộ theo role; profileVersion và outbox snapshot công khai ghi cùng transaction.

- Phase 3: sáu API Admin tạo/list/detail/PATCH profile/PATCH status/soft DELETE; bảo vệ self/last Admin, revoke refresh và ghi account/profile/email outbox cùng transaction.

- Phase 4: upload/read avatar JPEG/PNG/WebP, lưu file bền vững ngoài webroot, metadata/profile/outbox atomic và cleanup orphan24h. ADMIN avatar chỉ owner đọc, GET me vẫn profile=null.

- Phase5: Kafka relay và email worker độc lập; verify phát public snapshot atomic, bootstrap profile qua job opt-in. Migration V3 thêm delivery_sequence/index để giữ thứ tự cùng account/channel. Delivery được bật bằng cấu hình bên dưới.

- Phase6 đã có consumer opt-in, PENALTY/COMPLETION_REWARD/REVERSAL, history/dedup/score0 lock/revoke/outbox và retry/DLT. Listener mặc định tắt đến khi Payment bàn giao producer/fixture mới với mọi reversal version2. Tích hợp Trading/Payment/Notification thật và SMTP production thuộc Phase7.

## API Admin — Phase 3

Tất cả endpoint dưới yêu cầu Bearer của ADMIN ACTIVE; response có `Cache-Control: private, no-store`.

| Method | Path | Request / kết quả |
| --- | --- | --- |
| POST | `/api/v1/admin/accounts` | Body giống register (xem `docs/fixtures/register-farmer.json`), chỉ FARMER/TRADER; 201 UNVERIFIED + verificationEmailStatus |
| GET | `/api/v1/admin/accounts?page=0&size=20&role=FARMER&status=ACTIVE` | `{items,page,size,totalElements,totalPages}`, size tối đa100; createdAt DESC/accountId DESC |
| GET | `/api/v1/admin/accounts/{accountId}` | AccountSummary + emailVerifiedAt + profile; ADMIN profile=null |
| PATCH | `/api/v1/admin/accounts/{accountId}` | Chỉ sửa field profile hiện diện; xem `docs/fixtures/admin-profile-patch.json`; 200 detail |
| PATCH | `/api/v1/admin/accounts/{accountId}/status` | `{status,reason}`, xem `docs/fixtures/admin-status-patch.json`; 200 AccountSummary |
| DELETE | `/api/v1/admin/accounts/{accountId}` | 204, soft INACTIVE, giữ account/profile |

AccountSummary đúng sáu field `accountId,username,email,role,status,createdAt`. Không trả flags/version/reputation nội bộ. Profile PATCH giữ field vắng, cho null ở optional; không nhận role/email/password/status/reputation. Avatar chỉ nhận reference media đã attached thuộc account đích; chưa có upload API ở Phase 3.

Self-lock/self-disable/self-delete trả409. Mọi Admin mutation lấy cùng PostgreSQL transaction advisory lock trước account row lock và kiểm lại caller ACTIVE. Request đã qua filter nhưng Admin vừa bị vô hiệu trả409; request mới dùng JWT cũ trả403. Không thể vô hiệu cả hai Admin đồng thời. Restore cần verified, không reputation_lock và score>0 với FARMER/TRADER; ADMIN có score=null. Khóa lại account đã reputation-lock vẫn ghi admin_lock độc lập, không reset điểm.

LOCKED/INACTIVE revoke mọi refresh trong transaction. Khi đổi trạng thái/public profile, ghi outbox cùng business transaction; lỗi ghi email intent cũng rollback status/token/version/Kafka intent. Account Kafka payload chỉ `{accountId,reasonCode,accountVersion}`, reasonCode ổn định `ADMIN_LOCK`/`ADMIN_UNLOCK`; lý do tự do chỉ ở channel EMAIL, không đưa lên Kafka hoặc log. Gửi OTP tạo account sau commit; SMTP lỗi vẫn giữ account UNVERIFIED và trả201/FAILED. Relay và gửi mail khóa/mở thuộc Phase 5.

## Avatar — Phase 4

`POST /api/v1/users/me/avatar` nhận Bearer ACTIVE, multipart field `file`, tối đa5MiB/20MP. Chấp nhận nội dung JPEG/PNG/WebP thật, không tin filename/MIME client. 201 trả `{mediaId,avatarUrl,contentType,sizeBytes}`; JPEG được re-encode JPEG, PNG/WebP được re-encode PNG, bỏ metadata/animation. Response mô tả file sau xử lý; output lớn hơn5MiB trả413.

Ví dụ (thay token/path bằng giá trị local, không lưu token vào file):

```sh
curl -X POST http://localhost:8081/api/v1/users/me/avatar \
  -H "Authorization: Bearer <access-token>" -F "file=@avatar.webp"
curl http://localhost:8081/api/v1/users/media/<mediaId> \
  -H "Authorization: Bearer <access-token>" --output avatar.png
```

Download trả bytes, Content-Type/Length, `Cache-Control: private, no-store` và `X-Content-Type-Options: nosniff`. Owner ACTIVE đọc ảnh đã attached của mình; người khác ACTIVE chỉ đọc avatar hiện hành của Farmer/Trader ACTIVE. Ảnh cũ không còn public. Không được xem/không có media trả404; sai định dạng415, quá giới hạn413, file storage lỗi/corrupt503. Thiếu file multipart400. Không trả storage path.

ADMIN lưu avatar trong media_assets, chỉ owner đọc theo mediaId từ response upload; GET me vẫn profile=null, không có public profile/event và không API list media. Farmer/Trader cập nhật profile/avatar cùng version/outbox trong transaction; relay thuộc Phase5.

Local: đặt `MEDIA_STORAGE_DIR=./local-data/media` trong `.env` hoặc absolute path ngoài webroot. Docker Compose dùng `/var/lib/user-media` trên volume `user-media-data`; giữ volume khi restart/recreate app. Không chạy `docker compose down -v` trên môi trường cần giữ DB/media. Backup media volume cùng user_db, khôi phục cùng snapshot nhất quán. Nhiều replica phải chia sẻ cùng volume và DB.

File phải lưu xong trước commit metadata/profile/outbox. Lỗi DB có thể để lại orphan; cleanup03:30 UTC dọn file/metadata unattached quá24h, giữ mọi attached media. DB unavailable thì cleanup giữ file. Không xóa avatar cũ đã attached bằng job orphan. Đã kiểm JAR thật: upload HTTP, restart JVM với cùng DB/media directory rồi tải lại ảnh đúng bytes; servlet thật trả413 khi multipart quá5MiB. Image/restart deployment/backup restore trên môi trường bàn giao được nghiệm thu Phase7.

`verify` Phase4 đạt **117 test** (35 unit/Web/security +82 integration), 0 failure/error/skipped; thêm10 unit image/storage và15 integration avatar. Log local `.tools/phase4-verify.log`.

## Outbox/Kafka/email — Phase 5

Business update và outbox commit cùng transaction. Relay lấy pending row bằng `FOR UPDATE SKIP LOCKED`, gửi Kafka key=accountId với payload/eventId đã lưu; chỉ set published_at sau broker ACK. Lỗi retry theo5s,10s,20s… tối đa1h, giữ nguyên eventId/payload và không tự drop record. Bản gửi có thể lặp khi crash sau ACK hoặc SMTP đã nhận nhưng DB chưa commit: **at-least-once**, consumer phải dedup/version guard. Producer idempotence không bảo đảm exactly-once giữa DB và Kafka.

V3 backfill backlog cũ theo created_at/eventId; event mới dùng sequence tăng dần, kể cả timestamp bằng nhau. Cùng account/channel, head chưa publish chặn các row sau; account khác và channel khác vẫn tiếp tục. Hai scheduled worker dùng scheduler pool2; mỗi lần xử lý tối đa20 row, commit từng row. Không giữ account row lock khi gửi network.

Delivery opt-in mặc định tắt để không tự gửi backlog trước khi có môi trường đích. Điền trong `.env` khi chạy profile local (hoặc env/secret ở deployment):

```properties
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KAFKA_PORT=9092
OUTBOX_KAFKA_ENABLED=true
OUTBOX_EMAIL_ENABLED=true
PROFILE_BOOTSTRAP_ENABLED=false
SUPPORT_EMAIL=<your-support-email>
MAIL_FROM=<your-verified-sender>
```

Email worker bật mà MAIL_FROM/SUPPORT_EMAIL thiếu hoặc sai định dạng sẽ fail startup. Gửi khóa/mở chỉ cho email đã verify, bằng SMTP riêng; nội dung khóa có SUPPORT_EMAIL, không gửi reason tự do/bằng chứng/contact bên kia. SMTP lỗi không rollback account hoặc chặn Kafka; Kafka không nhận payload EMAIL/OTP/token/password.

Kafka local single-broker KRaft:

```sh
docker compose --profile messaging up -d user-kafka user-mail
```

App chạy host dùng localhost:9092; container User dùng user-kafka:19092. Nếu đổi KAFKA_PORT thì đổi bootstrap host tương ứng. Compose đã smoke test external advertised listener bằng Kafka AdminClient trên project biệt lập, rồi dọn project test. Broker local dùng PLAINTEXT/auto-create topic và không phải cấu hình production hay bảo đảm lưu trữ cluster. Deployment cần topic/partition/replication/security/retention do các owner thống nhất; Kafka properties/secrets được inject bằng cấu hình Spring Kafka, không commit credential.

| Event | Topic/key | Trigger |
| --- | --- | --- |
| USER_PROFILE_UPDATED | soa.user.profile.v1 / accountId | Verify ACTIVE, PUT/PATCH public profile, avatar, Admin đổi visibility, bootstrap |
| ACCOUNT_LOCKED/ACCOUNT_UNLOCKED | soa.user.account.v1 / accountId | Admin lock/restore; accountVersion và recipient/reference ACCOUNT/accountId |

ADMIN không phát public profile. Khi ẩn: publicVisible=false/profile=null. Bootstrap opt-in qua `PROFILE_BOOTSTRAP_ENABLED=true` trong một lần chạy, sau đó trả false và restart cấu hình bình thường. Job keyset paging100, khóa từng account đọc state hiện tại và ghi snapshot; profile_version0 được nâng1, version hiện có giữ nguyên. Chạy lại tạo snapshot event mới cùng version, replica phải bỏ duplicate/old version. Không có public bootstrap API và không đọc user_db từ Trading.

Quan sát pending/retry bằng `scripts/Outbox-Status.sql`; query không in payload email. Backlog tăng/attempt_count tăng cần kiểm tra broker/topic/SMTP/config. Sửa nguyên nhân rồi đợi retry; không xóa pending, sửa payload hoặc đổi eventId để ép qua. Nếu cần replay một Kafka event đã publish, phối hợp owner consumer, giữ nguyên eventId/payload, chỉ reset marker/available_at của đúng event trong DB bằng thao tác vận hành có kiểm soát. Không tự replay email vì có thể gửi trùng. Chưa có DLT consumer ở Phase5; retry/DLT quyết định Payment→User thuộc Phase6.

Fixtures giao cho owner: `docs/fixtures/user-profile-updated.json`, `account-locked.json`, `account-unlocked.json`; consumer profile fixture tại `src/test/java/vn/agriculture/soa/user/fixtures/ProfileReplica.java`. Đây là fixture hợp đồng, chưa chứng minh repo Trading/Notification thật đã tích hợp.

`clean verify` ngày02/11/2026 đạt **133 test** (36 unit/Web/security +97 integration), 0 failure/error/skipped. Phase5 thêm16 test, gồm13 Kafka/PostgreSQL/Mailpit integration và các test verify rollback, Admin email verified, mail config. Log local `.tools/phase5-verify.log`.

## Reputation — Phase 6

Xem `docs/reputation-contract-and-recovery.md` cho contract và recovery. User chỉ xử lý quyết định đã xác nhận từ Payment; không tự phán vi phạm hoặc chạy job thưởng72h. Fixtures penalty/reward/reversal trong docs/fixtures là mẫu phía User, không phải bằng chứng producer Payment đã tích hợp.

Consumer `soa.payment.reputation-decision.v1`, key accountId, group `user-reputation-decision-v1`; JSON source payment-service và eventVersion1. Validate role/mã/mức điểm, clamp0..10/bước0,5; reward+0,5 và cap1 điểm/account/counterparty/30ngày. Record cap hit/ceiling vẫn ghi actualDelta0 và không cộng lại sau. Duplicate event/business key không áp hai lần; payload khác dưới key cũ là conflict. V4 bổ sung fingerprint/history metadata và event receipts, không sửa migration cũ.

Score0 đặt reputation_lock, revoke refresh, giữ nguyên INACTIVE/admin_lock, chặn JWT cũ trên request mới và ghi REPUTATION_CHANGED/account/profile visibility outbox cùng transaction. ACK sau DB commit; crash trước ACK nhận lại nhưng không đổi điểm lần hai. REVERSAL trỏ trực tiếp gốc phạt/thưởng, đảo actualDelta đã lưu rồi clamp; requestedDelta chỉ lưu đối chiếu. Một gốc chỉ đảo một lần, không đảo reversal hoặc tự phục hồi thưởng đã thu hồi. Score>0 sau reversal bỏ reputation_lock nhưng giữ status và chờ Admin mở ACTIVE; không tác động tiền/cọc.

Base decisionVersion=1, mọi REVERSAL=2; không phải version chung account/Contract. User chấp thuận chuẩn hóa reversal thưởng từ1 thành2: **Payment cần sửa producer và bàn giao fixture mới trước khi bật `REPUTATION_CONSUMER_ENABLED=true`**. Lỗi DB/ordering/thiếu gốc được giữ retry, không ACK bỏ qua. Envelope/policy/version conflict không retryable thử3 lần rồi forward `soa.user.reputation-decision.dlt.v1`, chỉ commit nguồn khi broker ACK DLT. DLT private giữ input gốc và metadata nguồn tối thiểu; không copy headers/stack trace hoặc log raw payload. Không dùng lịch/topic retry Notification.

Replay opt-in qua `REPUTATION_REPLAY_ENABLED`/`REPUTATION_REPLAY_FILE` với JSONL được Payment phê chuẩn; không có HTTP API đổi điểm. Dừng listener, đối soát thứ tự trước replay, không rewrite history/score hoặc tự nâng offset. Thiếu gốc thì replay gốc hợp lệ trước rồi retry reversal. Xem `scripts/Reputation-Status.sql` và tài liệu recovery.

Kết quả kiểm thử Phase 6 được ghi trong `docs/implementation-decisions.md` và `docs/user-service-roadmap.md`; nghiệm thu producer Payment và Notification thực tế thuộc Phase 7.

## Chạy local trên máy

Yêu cầu JDK 21 (`java -version`, `javac -version`), Docker Desktop đang chạy và Internet cho lần tải Maven/dependency/image đầu tiên. Wrapper giúp không cần cài Maven riêng.

PowerShell, chạy từ thư mục project:

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
# Điền DB_PASSWORD và INTERNAL_*_KEY cần sử dụng vào .env.
java scripts/GenerateLocalAuth.java
docker compose up -d user-db user-mail
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

Linux/macOS:

```sh
test -f .env || cp .env.example .env
# Điền các giá trị trống trước khi chạy.
java scripts/GenerateLocalAuth.java
docker compose up -d user-db user-mail
sh ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Profile `local` đọc `.env` như file Java properties; không dùng `export`, không bọc giá trị bằng dấu nháy. Giữ giá trị là chuỗi đơn giản (key ngẫu nhiên dạng hex), tránh ký tự backslash/interpolation. Biến môi trường thật có ưu tiên cao hơn file. Nếu đổi DB_PORT, cập nhật DB_URL tương ứng. `.env` bị Git ignore.

Nếu dùng PostgreSQL đã cài trên Windows, bỏ bước `docker compose up -d user-db` và dùng credential của server đó trong `.env`. `user_db` trong DB_URL là **tên database**; DB_USERNAME phải là **user/role đăng nhập thực sự tồn tại**, không tự động là tên database. Ví dụ server local dùng user `postgres` thì đặt `DB_USERNAME=postgres` và mật khẩu của user đó. DB_PORT thường là 5432; SERVER_PORT=8081 là port HTTP của ứng dụng. Sửa `.env.example` không cấu hình ứng dụng: cần file `.env` và phải bật profile `local` bằng lệnh ở trên hoặc cấu hình Active profiles trong IDE.

Với PostgreSQL Windows, chỉ cần `docker compose up -d user-mail` để bật mail sink. Mailpit UI ở `http://localhost:8025`, SMTP localhost:1025; xem OTP trong inbox local, không ở log ứng dụng. Auth mặc định bật nên phải chuẩn bị key/OTP secret bằng lệnh Java trước khi chạy. Script tạo RSA 3072-bit trong `secrets/local-auth/` và OTP secret ngẫu nhiên trong `.env`; các file này bị ignore. Chạy lại script giữ key/secret và các giá trị `.env` không trống, không đổi mật khẩu DB.

Không để optional `MAIL_HOST`, `MAIL_PORT`, `MAIL_SMTP_AUTH`, `MAIL_STARTTLS`, `MAIL_FROM` thành giá trị rỗng trong `.env`: hoặc bỏ/comment dòng để dùng mặc định local, hoặc điền đúng. Production phải cung cấp key/secret/SMTP/sender thật; không chạy script local để tự gán credential production.

Khi chạy lại, giữ nguyên `.env` đã điền; không chép `.env.example` đè lên file này. Nếu lỗi `SCRAM-based authentication, but no password was provided`, kiểm tra `DB_PASSWORD` trong `.env` có giá trị, working directory là thư mục project và profile `local` đang bật. Build `package` kiểm tra code/test Web có thể thành công trong khi `spring-boot:run` vẫn lỗi credential: chỉ lệnh chạy ứng dụng mới kết nối DB local. Dùng tham số `-Dspring-boot.run.profiles=local` mỗi lần chạy để không phụ thuộc biến profile của phiên terminal trước.

Tạo mỗi service key bằng ít nhất 32 byte ngẫu nhiên (ví dụ PowerShell dưới đây sinh 64 ký tự hex; tự lưu vào `.env` và cấu hình caller tương ứng):

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
[BitConverter]::ToString($bytes).Replace('-', '')
```

Key trống vô hiệu caller; key ngắn hoặc key dùng chung giữa hai caller làm ứng dụng từ chối khởi động. Không có key mẫu production. `DB_PASSWORD` phải được điền; Compose không tự gán mật khẩu. Không đổi credential của volume PostgreSQL đang có chỉ bằng sửa `.env`.

## Chạy cả ứng dụng bằng Compose

Sau khi điền `.env`:

```powershell
docker compose --profile app up -d --build
```

Ứng dụng trong container dùng `user-db:5432`, không dùng localhost cho DB. PostgreSQL và port 8081 chỉ bind `127.0.0.1` của host; DB có named volume riêng. Đây là cấu hình local, không phải deployment public. Deployment cần private network/HTTPS và Gateway chặn `/internal/**`; các thay đổi Gateway thuộc repo khác.

## Endpoint hiện có

```text
GET http://localhost:8081/actuator/health
GET http://localhost:8081/actuator/health/liveness
GET http://localhost:8081/actuator/health/readiness
GET http://localhost:8081/internal/v1/accounts/{UUID}/access-state
X-Service-Key: <key riêng của caller đã cấu hình>
```

API authentication:

| Method/path | Request | Kết quả |
| --- | --- | --- |
| POST `/api/v1/auth/register` | username,email,password,role,profile đúng role | 201 account UNVERIFIED + verificationEmailStatus SENT/FAILED |
| POST `/api/v1/auth/verify-email` | email,otp | 200 status ACTIVE |
| POST `/api/v1/auth/resend-verification` | email | 202 generic; 503 nếu SMTP cần gửi bị lỗi |
| POST `/api/v1/auth/login` | username,password | 200 TokenPair, account phải ACTIVE |
| POST `/api/v1/auth/refresh` | refreshToken | 200 TokenPair mới, token cũ bị revoke |
| POST `/api/v1/auth/logout` | refreshToken + Bearer ACTIVE của đúng owner | 204 |

TokenPair gồm accessToken,refreshToken,tokenType=Bearer,expiresIn=900,accountId,role. Access JWT RS256: iss=urn:soa:user-service, aud=soa-api, sub UUID, role đơn, iat/exp/jti và header kid; refresh opaque 32 byte ngẫu nhiên, chỉ lưu SHA-256, 7 ngày/token. Token response no-store, không đặt cookie. Frontend giữ token trong memory theo BA; frontend chưa thuộc repo này. Logout không thu hồi access JWT còn hạn, nhưng refresh tương ứng bị revoke; kiểm current account state chặn JWT sau khóa.

OTP 6 số/10 phút/5 lần sai; resend 60 giây, tối đa 5 mã/giờ/email gồm register. Register commit account/profile/OTP trước SMTP; lỗi SMTP vẫn 201/FAILED và account UNVERIFIED, không xóa account. Verify/resend khóa account rồi OTP. Resend khi email không có/không cần verify trả 202. Daily cleanup 03:00 UTC không xóa lịch sử quota còn hiệu lực. Sai username/password trả 401; sau 10 lần sai/IP+username trong 15 phút trả 429/Retry-After. Không lấy X-Forwarded-For từ client để đổi bucket.

Fixture test-only tại `docs/fixtures/register-farmer.json`; thay username/email trước khi thử, lấy OTP từ mail sink. Không dùng fixture password cho seed/production. Register ADMIN bị từ chối; avatarUrl khi register phải null hoặc vắng. Upload avatar sau khi verify/login qua endpoint Phase4.

## Profile API — Phase 2

Các endpoint đều yêu cầu Bearer của account ACTIVE, kể cả đọc public profile. Identity của `/me` lấy từ JWT `sub`.

| Method/path | Kết quả |
| --- | --- |
| GET `/api/v1/users/me` | Private DTO của chính caller; ADMIN có profile=null |
| PUT `/api/v1/users/me` | Thay toàn bộ profile Farmer/Trader; ADMIN trả 400 |
| GET `/api/v1/users/{accountId}` | Public profile Farmer/Trader ACTIVE |
| GET `/api/v1/farmers/{farmerId}` | Public profile Farmer ACTIVE |
| GET `/api/v1/traders/{traderId}` | Public profile Trader ACTIVE |

PUT nhận object profile trực tiếp, không bọc trong `profile`. Ví dụ Farmer:

```json
{"fullName":"Farmer demo","province":"Dong Thap","district":"Cao Lanh","avatarUrl":null}
```

Farmer bắt buộc `fullName`, Trader bắt buộc `businessName`; tên không được rỗng sau trim. Field tùy chọn vắng sẽ trở thành null: phone,address,province,district,avatarUrl và buyingArea riêng Trader. Từ chối field không thuộc role hoặc field account như role/email/password/status/reputation, kể cả giá trị null. Không hỗ trợ PATCH.

Private response gồm accountId,username,email,role,status,createdAt,emailVerifiedAt,profile. Public response chỉ có sáu field accountId,displayName,avatarUrl,province,district,joinedAt; joinedAt là accounts.created_at. Không lộ email/phone/address/buyingArea/reputation. Response dùng `Cache-Control: private, no-store`. Caller bị khóa trả 403; target ADMIN/sai role/không ACTIVE/không tồn tại trả 404; lỗi persistence trả 503 DEPENDENCY_UNAVAILABLE.

avatarUrl khác null chỉ chấp nhận đường dẫn tương đối `/api/v1/users/media/{UUID}` chuẩn, metadata thuộc chính owner/resource và đã attached. URL ngoài hoặc media tạm/owner khác bị từ chối. Upload/lưu file/API đọc media đã có ở Phase4; dùng avatarUrl từ response upload hoặc null.

PUT khóa account, cập nhật profile, tăng profileVersion và ghi snapshot public vào outbox trong cùng transaction; outbox lỗi sẽ rollback. PUT lặp cùng dữ liệu không tạo event/version mới. Chỉ đổi phone/address/buyingArea không đổi public version và không tạo public event. account_version không đổi khi sửa profile. Dùng schema V1/V2 hiện có, không thêm migration.

Outbox topic `soa.user.profile.v1`, aggregate_id=accountId (Kafka key), event USER_PROFILE_UPDATED/version1, recipientIds=[]; snapshot chỉ chứa displayName,avatarUrl,province,district,joinedAt. Phase5 đã có relay, trigger verify/profile/status/avatar và bootstrap. Delivery cần bật cấu hình; đồng bộ Trading thật được nghiệm thu Phase7.

Fixtures: `docs/fixtures/put-farmer-profile.json`, `put-trader-profile.json`, `user-profile-updated.json`. Lần kiểm chứng Phase 2 đạt 69 test. Sau Phase 3, `clean verify` ngày 02/11/2026 đạt **92 test** (25 unit/Web/security + 67 integration), gồm 19 test profile và 23 test Admin; không failure/error/skipped. Log local `.tools/phase3-verify.log`, report tại `target/surefire-reports` và `target/failsafe-reports`.

Trên Windows, dừng process đang chạy JAR trước khi Maven repackage để tránh khóa file. Dùng `spring-boot:run` khi phát triển local.

## Demo API local

Khi service đang chạy với SMTP trỏ Mailpit localhost:1025:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Demo-Auth.ps1
```

Script tạo một Farmer demo mới và chạy register → OTP đọc từ Mailpit → login → refresh/logout; đồng thời kiểm tra login trước OTP, cooldown, refresh cũ và public register ADMIN bị chặn. Kết quả ở `.tools/demo-auth/latest.md`; mật khẩu Farmer chỉ lưu trong `.tools/demo-auth/account.env` bị Git ignore. OTP/token không ghi vào báo cáo hoặc console. Mỗi lần chạy tạo một account demo, không xóa dữ liệu hiện có.

Thêm `-IncludeAdmins` để xác minh OTP/đăng nhập/logout hai Admin đã seed từ `.env`; nếu đã ACTIVE thì chỉ đăng nhập/logout. Script chỉ đọc Mailpit local, không đọc/gửi thư qua hộp mail bên ngoài. Admin password đọc từ `.env`, không in ra console.

## Seed Admin và rotation key

Trong `.env`, cấu hình ít nhất hai identity riêng qua ADMIN_1_USERNAME/EMAIL/PASSWORD và ADMIN_2_USERNAME/EMAIL/PASSWORD, rồi đặt ADMIN_SEED_ENABLED=true. Cả email, username và password phải khác nhau; không có giá trị mặc định. Mỗi Admin tạo UNVERIFIED, score null, không profile Farmer/Trader; phải dùng verify-email trước khi login. SMTP seed lỗi không rollback account; dùng resend-verification sau cooldown. Restart không reset password/status hoặc phát lại OTP. Identity trùng account không phù hợp làm startup báo conflict, không tự merge.

Local có thể để ADMIN_SEED_ENABLED=false khi chưa có thông tin thật; trước demo/tích hợp cần bật và verify hai Admin. Việc có code seed không đồng nghĩa đã tạo hai Admin thật trên môi trường của bạn.

Deploy current private/public PEM PKCS8/X509 qua JWT_PRIVATE_KEY_PATH, JWT_PUBLIC_KEY_PATH, JWT_KEY_ID. Khi rotate, cung cấp thêm JWT_PREVIOUS_KEY_ID và JWT_PREVIOUS_PUBLIC_KEY_PATH, giữ public key cũ ít nhất 16 phút sau lần phát JWT cuối bằng key cũ; chỉ giữ private key hiện hành. Backend khác nhận cùng public key set qua deployment. Không OIDC discovery/JWKS API. Clock skew tối đa 60 giây. Đổi OTP_HMAC_SECRET làm mã OTP đang lưu không còn xác minh được; không tự đổi secret mỗi restart.

Access-state response 200:

```json
{"accountId":"42c4b826-6633-4636-8e2f-13638973c3d9","status":"ACTIVE","role":"FARMER","accessAllowed":true}
```

UNVERIFIED/LOCKED/INACTIVE vẫn trả **200 với `accessAllowed:false`** cho caller nội bộ; Gateway/backend chịu trách nhiệm chuyển protected request thành 403. Thiếu/sai key: 401 UNAUTHORIZED; key không cấp quyền endpoint/method khác: 403 FORBIDDEN; UUID sai: 400 VALIDATION_ERROR; account không tồn tại: 404 ACCOUNT_NOT_FOUND; DB lỗi: 503 ACCESS_STATE_UNAVAILABLE. `X-User-Id`, `X-User-Role`, Bearer token không thay service key. UUID chưa được register hoặc seed sẽ trả 404.

Caller phải đặt **timeout tổng 1 giây** và fail closed khi lỗi/timeout; server đặt JDBC query timeout 1 giây và pool acquisition timeout 500ms, không coi chúng là bảo đảm tổng thời gian response luôn dưới 1 giây. DB_URL phải trỏ primary, không read replica. Không dùng issuer discovery cho URN, không có JWKS endpoint.

## Build và kiểm thử

```powershell
.\mvnw.cmd verify
```

`verify` chạy test Web/security rồi integration test PostgreSQL thật qua Testcontainers (`postgres:17-alpine`), migrate bằng Flyway, khởi động Spring context và kiểm role/profile/unique/score, row locking, quota cleanup, access-state sau commit và health. Không tự bỏ qua IT nếu Docker thiếu; lệnh sẽ báo lỗi rõ ràng. Không dùng H2 thay PostgreSQL.

Test auth bổ sung OTP/SMTP failure, commit-before-send, quota sau cleanup, verify/resend/refresh đồng thời, refresh-vs-lock, ownership/logout, login rate limit, JWT sai chữ ký/algorithm/kid/claims/thời hạn và previous key. SMTP adapter được kiểm tra bằng Mailpit Testcontainers thật, gồm transport failure. Testcontainers không dùng credential `.env` hoặc user_db local. Bài foundation IT có thể dùng database test riêng theo lệnh dưới; bài auth/SMTP IT vẫn cần Docker.

Chỉ chạy test Web/security và đóng gói khi Docker chưa sẵn sàng:

```powershell
.\mvnw.cmd package
```

`package` không chạy Failsafe IT, do đó không thay thế kết quả `verify`.

Có thể chạy IT với PostgreSQL riêng: phải dùng **database test rỗng, có thể xóa dữ liệu**, vì mỗi test `TRUNCATE accounts CASCADE`; tuyệt đối không trỏ user_db đang dùng. Credential chỉ qua biến môi trường:

```powershell
$env:TEST_DB_PASSWORD = '<password của database test>'
.\mvnw.cmd '-Dtest.db.url=jdbc:postgresql://localhost:5432/user_service_test' '-Dtest.db.username=user_service_test' verify
```

Report: `target/surefire-reports/`, `target/failsafe-reports/`. JAR: `target/user-service-0.1.0-SNAPSHOT.jar`.

Kiểm tra JAR thật ở port 8081 với DB Compose tạm biệt lập (port 8081 phải trống):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Smoke-Local.ps1
```

Script kiểm local profile, health, key 401, account 404, ACTIVE/LOCKED sau commit và 503 khi DB thật dừng. Nó tự dừng JVM và xóa đúng Compose project/volume test sinh ngẫu nhiên; không dùng volume local thông thường. Credential test ngẫu nhiên nằm trong `.tools/` bị ignore, không ghi vào `.env.example`.

Smoke-Local là kiểm tra nền nên cố ý tắt auth; kết quả này không thay thế auth/SMTP IT.

## Quyết định và công việc tiếp theo

Xem `docs/implementation-decisions.md` cho đối chiếu BA cũ/v2.1, schema kỹ thuật và phạm vi chưa triển khai. Cần DB credential, OTP secret, RSA keys/kid, SMTP/sender; email/password riêng hai Admin để seed thật. SUPPORT_EMAIL và Kafka cần cấu hình khi bật delivery Phase5. Không dùng dữ liệu test để đánh dấu cấu hình production hoàn tất.
