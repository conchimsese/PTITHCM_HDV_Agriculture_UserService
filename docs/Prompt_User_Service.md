# Prompt vibe code User Service

Dán nội dung dưới vào coding agent tại repo User, kèm BA - SOA.docx. Prompt này chỉ định cách triển khai, không tự giải quyết các vấn đề nghiệp vụ còn thiếu trong bản BA.

---

Bạn là senior Java/Spring Boot engineer. Hãy trực tiếp triển khai User Service MVP cho dự án Agriculture SOA trong workspace hiện tại, dựa trên phần Users Service và CONTRACT CHUNG MVP v2.1 của BA - SOA.docx. Không chỉ lập kế hoạch: đọc repo, thực hiện code, migrations, kiểm thử và README theo từng giai đoạn chạy được.

## 1. Cách làm và nguồn sự thật

- Đọc AGENTS.md, cấu trúc repo, build/config/migration/tests và tài liệu đính kèm trước khi sửa. Không xóa hay dựng lại codebase đang có.
- Nếu thiếu file BA hoặc không đọc được nội dung, báo đúng tài liệu cần cung cấp; không tự giả định đã đọc.
- Ưu tiên quyết định người dùng mới nhất → Conflict đã chốt → Contract v2.1 → rule → spec. Khi đoạn cũ trái v2.1, ghi đối chiếu trong docs/implementation-decisions.md và dùng v2.1.
- Chỉ sửa repo User và tài liệu/test của User. Không sửa Payment/Trading/Notification/Gateway nếu chưa được yêu cầu; cung cấp integration fixtures cho các owner.
- Tiếp tục phần không bị chặn. Với mâu thuẫn nghiệp vụ chưa giải được từ tài liệu, cô lập đúng nhánh và hỏi ngắn; không dừng cả repo, không tự duyệt yêu cầu mới.
- Dùng Java21, Spring Boot3.x theo version hiện có. Nếu repo trống, dùng hướng Boot3.5.x/Maven của BA; chọn patch và dependencies tương thích sau khi kiểm tài liệu chính thức. Không tự nâng major hoặc thêm Redis/Eureka/Keycloak/event sourcing.
- Stack cần: Spring Web, Validation, Security/OAuth2 Resource Server + JWT signing, JPA, PostgreSQL, Flyway, Mail, Spring Kafka, Actuator và bộ test phù hợp. Versions theo BOM, không pin riêng tùy tiện.

## 2. Phạm vi dữ liệu

- user_db riêng, không truy cập DB service khác hoặc FK xuyên DB. UUID xuyên API/event, UTC TIMESTAMPTZ, DTO camelCase/DB snake_case.
- Năm bảng nền: accounts, farmer_profiles, trader_profiles, email_verification_otps, refresh_tokens.
- Baseline còn yêu cầu reputation_history, outbox, media_assets; không áp quy tắc “chỉ5 bảng” cũ để bỏ các bảng này.
- accounts bổ sung reputation_score NUMERIC(3,1), 0..10 bước0,5 cho FARMER/TRADER, ADMIN null; account_version/profile_version; admin_lock/reputation_lock độc lập.
- Profile ID = accountId đúng role. Farmer fullName, Trader businessName; phone/address/buyingArea riêng tư. Không verification_status, biological_standard, truck_support, giấy tờ/approval/badge/rating.
- Schema kỹ thuật cần cột/index để bảo đảm concurrency/outbox/quotas được thiết kế có giải thích và migration. Không tự thêm nghiệp vụ, role hoặc API.

## 3. Authentication và security

- Public register chỉ FARMER/TRADER; account UNVERIFIED + đúng profile + OTP trong transaction. Verify hợp lệ mới ACTIVE; OTP không kích hoạt LOCKED/INACTIVE.
- Username/email trim+lowercase trước UNIQUE; không tự merge dữ liệu lịch sử. Password BCrypt; ít nhất8 ký tự có hoa/thường/số/đặc biệt, reject input UTF-8 >72byte.
- OTP6 số,10phút,5 lần sai/mã; resend60giây,max5 mã/giờ/email gồm lần register; HMAC-SHA-256 secret, không lưu/log mã gốc. Row locks chống verify/resend race. Cleanup không được làm reset quota; thiết kế quota tồn tại độc lập đúng theo BA.
- User gửi SMTP. Register commit trước SMTP, response201 có verificationEmailStatus SENT/FAILED; resend202 generic và503 nếu gửi cần thiết bị lỗi theo spec. Không rollback/xóa account do lỗi mail.
- Login chỉ ACTIVE; limit10 lần sai/15phút theo IP+username,429/Retry-After. Không tin X-Forwarded-For tùy ý ngoài proxy tin cậy.
- JWT RS256,15phút,iss urn:soa:user-service,aud soa-api,sub UUID,role đơn,iat/exp/jti,kid. Chỉ User giữ private key, public-key set theo deployment; overlap key≥16phút,clock skew≤60giây; không tự thêm JWKS API. Validate signature/algorithm/claims rõ, không dùng issuer-uri discovery cho URN không có OIDC endpoint.
- Refresh opaque≥32byte,7ngày/token,DB SHA-256 hash,rotate với row lock: chỉ1 refresh đồng thời thành công. Account lock trước token lock, transaction phát phiên và kiểm ACTIVE để không cấp phiên sau lock.
- TokenPair JSON và refresh/logout body {refreshToken}; logout Bearer ACTIVE, refresh token thuộc sub. Không tự chuyển sang cookie hoặc tạo frontend. Contract browser lưu memory-only thuộc frontend.
- Protected User API kiểm current state qua primary DB trực tiếp; không gọi HTTP lại chính User, không cache ACTIVE. GET /internal/v1/accounts/{accountId}/access-state trả accountId,status,role,accessAllowed và no-store; service key riêng caller/endpoint allowlist, không public. Account không có404, lỗi state503. Các caller timeout1giây.
- Seed tối thiểu2 Admin riêng từ secret/env, idempotent, UNVERIFIED và OTP trước ACTIVE; không hardcode password, không API tạo ADMIN.

## 4. REST/profile/Admin/media

Triển khai chính xác các endpoint ở User §6–7; gồm auth, GET/PUT me, GET users/farmers/traders public profile, Admin create/list/detail/profilePATCH/statusPATCH/softDELETE, internal access-state, avatar upload/read. Không đổi PUT me thành PATCH hoặc tự thêm API.

- Public chỉ caller ACTIVE/target Farmer hoặc Trader ACTIVE, whitelist accountId/displayName/avatarUrl/province/district/joinedAt. ADMIN target/sai role/không ACTIVE404. Không public reputation hoặc contacts.
- Private/Admin DTO như BA, không secret. Admin profile PATCH chỉ allowlist; role/email/password/status/reputation không được sửa tại profile endpoint.
- Page0,size20,max100,filter role/status,createdAt DESC/accountId DESC. DTO trang/error theo v2.1.
- Cấm self-lock/self-disable/self-delete và vô hiệu Admin ACTIVE cuối cùng; bảo vệ cả hai Admin đồng thời tự vô hiệu người còn lại, không chỉ COUNT ngoài transaction.
- LOCKED/INACTIVE revoke mọi refresh cùng transaction. Restore không bypass verified/score/lock flags; ADMIN không áp score>0 vì reputation=null.
- Avatar Farmer/Trader: JPEG/PNG/WebP≤5MiB/20MP, decode/re-encode; UUID storage key,volume riêng ngoài webroot; kiểm quyền metadata,không URL ngoài/path traversal. Metadata + profile + profile-event outbox commit nhất quán; không trả thành công nếu file chưa bền vững. Download Bearer/private,no-store,nosniff.
- Quyết định người dùng02/11/2026 về nhánh ADMIN avatar: lưu trong media_assets/resource_id=accountId, upload trả avatarUrl, chỉ owner ADMIN đọc; GET me giữ profile=null. Không tạo Farmer/Trader profile giả, không mở rộng private DTO/public event.

## 5. Kafka/outbox/reputation

- PostgreSQL outbox cùng transaction business; relay publish và đánh dấu sau broker xác nhận, giữ eventId/payload khi retry; at-least-once, không claim exactly-once DB/Kafka. Mail LOCKED/UNLOCKED retry riêng theo outbox, email lỗi không rollback lock; không đưa email/OTP/token vào business Kafka payload.
- Publish USER_PROFILE_UPDATED trên soa.user.profile.v1/key accountId theo v2.1 §9, version tăng; ẩn gửi publicVisible=false/profile=null; không contacts/reputation. Có job bootstrap snapshot, không public API mới chỉ để bootstrap.
- Consume CONTRACT_REPUTATION_DECIDED từ payment-service, topic soa.payment.reputation-decision.v1/key accountId, group user-reputation-decision-v1. Validate schema/source/action/UUID và toàn bộ data v2.1 §9; không tự kết luận ai vi phạm.
- PENALTY/REVERSAL/COMPLETION_REWARD theo danh mục F/T,D01–D07. Mức Farmer F01−1,F02−2,F03−2,F04−2/−3,F05−2,F06−3,F07−3,F08−5; Trader T01−1,T02−2,T03−2,T04−3,T05−3,T06−3,T07−5,T08−2. Không chấp nhận requestedDelta tùy ý ngoài policy.
- User lock account, kiểm business key, cập nhật score/history/flags/revoke/outbox trong một DB transaction; commit trước offset. Dedup ngay cả eventId mới nhưng cùng decisionId. Same key khác dữ liệu không silently accept.
- History có counterpartyId/requestedDelta/actualDelta/before/after/originalDecisionId/version. Reward unique contract/account,+0,5,max10,cap1 điểm/cặp/30 ngày; cap hit ghi actualDelta0,không cộng lại sau. Payment là owner job72h; User không tự tạo job xác nhận Contract.
- Reversal lấy ngược actualDelta gốc rồi clamp; unique originalDecisionId/account, không đảo hai lần. Thiếu gốc không ACK bỏ qua; không tự “đảo rồi áp lại” theo eventId. Kiểm chuỗi reward/reversal/penalty bị đổi thứ tự vì có thể làm sai clamp/lock.
- Score0: tự reputation_lock/LOCKED/revoke. Score>0 sau sửa/hủy chưa mặc nhiên cho phép mở nếu admin_lock/INACTIVE/chưa verified còn hiệu lực. Không reset1/10. Nhánh tự hay Admin mở sau reversal phải rõ trước khi claim hoàn chỉnh.
- Publish REPUTATION_CHANGED trên soa.user.reputation.v1 và ACCOUNT_LOCKED/UNLOCKED trên soa.user.account.v1 đúng payload/reference ACCOUNT/accountId/recipient accountId. accountVersion/profileVersion theo transaction.
- Consumer User decision chưa có lịch retry/DLT riêng trong BA: đề xuất cấu hình kỹ thuật riêng trong ADR, không dùng T.notification-retry-* hoặc ngầm nhận lịch Notification là quyết định User. Không mất event, không ACK trước apply/forward thành công.
- F04 cho phép −2/−3 theo kết luận Payment; User kiểm mã/mức hợp lệ, không tự yêu cầu bằng chứng hoặc suy outcome từ cọc. decisionId/version khi sửa quyết định chưa định nghĩa hết: cụ thể hóa liên kết reversal/gốc trước integration; ghi blocker cục bộ nếu chưa có invariant, không sửa producer contract âm thầm.

## 6. Giai đoạn và verification

Thực hiện tuần tự: (1) bootstrap + migrations + errors/security/access-state, (2) auth/OTP/SMTP/seed/rate limits, (3) profile/Admin/lock/media, (4) outbox/profile event, (5) reputation consumer/events/email. Sau mỗi giai đoạn chạy build/check phù hợp rồi tiếp tục phần được phép, không đợi xác nhận routine.

Tests tối thiểu: PostgreSQL thật/Testcontainers cho UNIQUE/row lock; register roleADMIN reject; OTP wrong/expired/reused/concurrent/resend quotas qua cleanup; normalized duplicate409; refresh concurrent chỉ1 thắng; refresh-vs-lock; oldJWT sau lock bị chặn; self/lastAdmin concurrent; public whitelist/ownership; upload giả MIME/quá size/owner; reputation replay cùng business key,partial rollback,cap,reversal thiếu gốc/double reversal/zero lock/admin flag; crash/outbox retry không mất event. Fixtures dùng test-only, không giả owner service đã triển khai.

Đầu ra gồm code,Flyway,config/Dockerfile/Compose local phạm vi User, .env.example không secret, README, fixtures và docs/implementation-decisions.md. Dùng dev/test mail sink khi thiếu credential thật; production không tự dùng địa chỉ/key giả. Không phát tán OTP/token/keys trong logs.

Cuối lượt báo: phần đã chạy, lệnh/tests và kết quả thực tế, việc còn blocked, giá trị môi trường cần cung cấp. Không đánh dấu module blocked là done; nếu test chưa chạy vì Docker/network phải nói rõ. Bắt đầu đọc repo và code ngay.
