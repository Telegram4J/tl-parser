package telegram4j.tl.json;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import telegram4j.tl.SchemaGeneratorConsts;
import telegram4j.tl.SourceNames;

import java.util.Optional;
import java.util.regex.Matcher;

@Value.Immutable
@JsonSerialize(as = ImmutableTlParam.class)
@JsonDeserialize(as = ImmutableTlParam.class)
public abstract class TlParam {

    public abstract String name();

    public abstract String type();

    @Value.Auxiliary
    @Value.Derived
    @JsonIgnore
    public String formattedName() {
        return SourceNames.formatFieldName(name());
    }

    @Value.Auxiliary
    @Value.Derived
    @JsonIgnore
    public Optional<Tuple2<Integer, String>> flagInfo() {
        if (!type().contains("?")) { // fast check
            return Optional.empty();
        }

        Matcher flag = SchemaGeneratorConsts.FLAG_PATTERN.matcher(type());
        if (!flag.matches()) {
            throw new IllegalStateException("Malformed flag param: " + this);
        }

        int pos = Integer.parseInt(flag.group(1));
        String type = flag.group(2);
        return Optional.of(Tuples.of(pos, type));
    }

    @Override
    public int hashCode() {
        int h = 5381;
        h += (h << 5) + name().hashCode();

        var flagInfo = flagInfo().orElse(null);
        if (flagInfo != null) {
            h += (h << 5) + flagInfo.getT2().hashCode();
        } else {
            h += (h << 5) + type().hashCode();
        }
        return h;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TlParam that = (TlParam) o;
        return name().equals(that.name()) &&
                flagInfo().isPresent() == that.flagInfo().isPresent() &&
                flagInfo().map(Tuple2::getT2).orElse(type()).equals(that.flagInfo().map(Tuple2::getT2).orElse(that.type()));
    }
}
