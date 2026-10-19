package vn.agriculture.soa.user.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;
import vn.agriculture.soa.user.account.AccountRole;
import static vn.agriculture.soa.user.auth.AuthSupport.invalid;
import static vn.agriculture.soa.user.profile.ProfileDtos.*;

/** PUT replaces the role's profile fields. Missing optional fields become null. */
final class ProfileUpdate {
    private static final String MEDIA_PREFIX = "/api/v1/users/media/";
    private static final Set<String> FARMER = Set.of("fullName","phone","address","province","district","avatarUrl");
    private static final Set<String> TRADER = Set.of("businessName","phone","address","province","district","avatarUrl","buyingArea");
    private ProfileUpdate() { }

    static Details parse(JsonNode body, AccountRole role, UUID owner, ProfileRepository repository) {
        if (role == AccountRole.ADMIN) { throw invalid("ADMIN has no editable Farmer/Trader profile"); }
        if (body == null || !body.isObject()) { throw invalid("Profile must be a JSON object"); }
        var fields = role == AccountRole.FARMER ? FARMER : TRADER;
        body.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) { throw invalid("Profile contains a field outside the role allowlist"); }
        });
        String nameKey = role == AccountRole.FARMER ? "fullName" : "businessName";
        String name = text(body,nameKey,role == AccountRole.FARMER ? 150 : 200);
        if (name == null || name.isBlank()) { throw invalid("Profile display name is required"); }
        String phone = text(body,"phone",20),address = text(body,"address",255);
        String province = text(body,"province",100),district = text(body,"district",100);
        String avatar = text(body,"avatarUrl",500);
        if (avatar != null) {
            try {
                if (!avatar.startsWith(MEDIA_PREFIX)) { throw new IllegalArgumentException(); }
                String suffix = avatar.substring(MEDIA_PREFIX.length());
                UUID mediaId = UUID.fromString(suffix);
                if (!mediaId.toString().equals(suffix) || !repository.ownsAttachedAvatar(mediaId,owner)) { throw new IllegalArgumentException(); }
            } catch (IllegalArgumentException ex) {
                throw invalid("avatarUrl must reference attached User media owned by this account");
            }
        }
        return role == AccountRole.FARMER
                ? new Farmer(name.trim(),phone,address,province,district,avatar)
                : new Trader(name.trim(),phone,address,province,district,avatar,text(body,"buyingArea",255));
    }

    private static String text(JsonNode body,String field,int maximum) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) { return null; }
        if (!value.isTextual() || value.textValue().length() > maximum) {
            throw invalid("Invalid profile field type or length");
        }
        return value.textValue();
    }
}
