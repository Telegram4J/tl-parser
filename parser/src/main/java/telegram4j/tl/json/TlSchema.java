package telegram4j.tl.json;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;
import telegram4j.tl.api.TlObject;

import java.util.List;

@Value.Immutable
@JsonSerialize(as = ImmutableTlSchema.class)
@JsonDeserialize(as = ImmutableTlSchema.class)
public interface TlSchema {

    List<TlEntityObject> constructors();

    List<TlEntityObject> methods();

    @JsonIgnore
    default String packagePrefix() {
        return "";
    }

    @JsonIgnore
    default Class<?> superType() {
        return TlObject.class;
    }
}
