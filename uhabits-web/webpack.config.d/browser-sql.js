// sql.js selects its browser implementation at runtime; Node-only branches are unused.
config.resolve = config.resolve || {};
config.resolve.fallback = { ...config.resolve.fallback, fs: false, path: false, crypto: false };
