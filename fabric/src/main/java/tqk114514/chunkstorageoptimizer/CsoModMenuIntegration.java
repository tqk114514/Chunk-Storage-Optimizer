package tqk114514.chunkstorageoptimizer;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * The Mod Menu hook: {@code Mods -> Chunk Storage Optimizer -> Config}.
 *
 * <p>Mod Menu is compiled against but never required ({@code modCompileOnly}), so this class is
 * simply never loaded when Mod Menu is absent and the config stays a plain properties file.
 */
public final class CsoModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return CsoConfigScreen::new;
    }
}
