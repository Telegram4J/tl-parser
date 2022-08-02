package telegram4j.tl.parser;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;
import telegram4j.tl.api.TlObject;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

@Value.Enclosing
class TlTrees {

    @Value.Immutable
    @JsonSerialize(as = ImmutableTlTrees.Scheme.class)
    @JsonDeserialize(as = ImmutableTlTrees.Scheme.class)
    static abstract class Scheme {

        public abstract List<Type> constructors();

        public abstract List<Type> methods();

        // auxiliary methods

        @Value.Default
        @JsonIgnore
        public String packagePrefix() {
            return "";
        }

        @Value.Default
        @JsonIgnore
        public Class<?> superType() {
            return TlObject.class;
        }
    }

    @Value.Immutable
    @JsonDeserialize(using = TypeDeserializer.class)
    @JsonSerialize(using = TypeSerializer.class)
    static abstract class Type {

        public abstract Kind kind();

        public abstract String name();

        public abstract String id();

        public abstract List<Parameter> parameters();

        public abstract String type();

        public enum Kind {
            CONSTRUCTOR,
            METHOD
        }
    }

    @Value.Immutable
    @JsonSerialize(as = ImmutableTlTrees.Parameter.class)
    @JsonDeserialize(as = ImmutableTlTrees.Parameter.class)
    static abstract class Parameter {

        public abstract String name();

        public abstract String type();

        // auxiliary methods

        @Value.Auxiliary
        @Value.Derived
        @JsonIgnore
        public String formattedName() {
            return SourceNames.formatFieldName(name());
        }

        @Value.Auxiliary
        @Value.Derived
        @JsonIgnore
        public Optional<Tuple3<Integer, String, String>> flagInfo() {
            if (type().indexOf('?') == -1) { // fast check
                return Optional.empty();
            }

            Matcher flag = SchemaGeneratorConsts.FLAG_PATTERN.matcher(type());
            if (!flag.matches()) {
                throw new IllegalStateException("Malformed flag param: " + this);
            }

            String flags = SourceNames.formatFieldName(flag.group(1));
            int pos = Integer.parseInt(flag.group(2));
            String type = flag.group(3);
            return Optional.of(Tuples.of(pos, flags, type));
        }

        @Override
        public int hashCode() {
            int h = 5381;
            h += (h << 5) + name().hashCode();

            var flagInfo = flagInfo().orElse(null);
            if (flagInfo != null) {
                h += (h << 5) + flagInfo.getT3().hashCode();
            } else {
                h += (h << 5) + type().hashCode();
            }
            return h;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Parameter that = (Parameter) o;
            return name().equals(that.name()) &&
                    flagInfo().isPresent() == that.flagInfo().isPresent() &&
                    flagInfo().map(Tuple3::getT3).orElse(type()).equals(that.flagInfo().map(Tuple3::getT3).orElse(that.type()));
        }
    }
}
