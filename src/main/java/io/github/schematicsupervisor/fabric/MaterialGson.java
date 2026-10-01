package io.github.schematicsupervisor.fabric;

import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import io.github.schematicsupervisor.core.Material;
import java.io.IOException;

/**
 * Writes a material as the name its enum used to have ({@code DIRT}), or a block material's ID, so
 * journals, probes and observations written before materials became a class read back unchanged.
 */
final class MaterialGson extends TypeAdapter<Material> {
    private MaterialGson() {
    }

    static GsonBuilder register(GsonBuilder builder) {
        return builder.registerTypeAdapter(Material.class, new MaterialGson().nullSafe());
    }

    @Override
    public void write(JsonWriter out, Material material) throws IOException {
        out.value(material.name());
    }

    @Override
    public Material read(JsonReader in) throws IOException {
        if (in.peek() != JsonToken.STRING) {
            throw new IOException("a material must be a JSON string");
        }
        try {
            return Material.fromJsonName(in.nextString());
        } catch (IllegalArgumentException unknown) {
            throw new IOException(unknown.getMessage(), unknown);
        }
    }
}
