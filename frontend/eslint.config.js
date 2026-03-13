import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import prettierConfig from 'eslint-config-prettier'
import globals from 'globals'

export default tseslint.config(
    js.configs.recommended,
    ...tseslint.configs.recommended,
    ...pluginVue.configs['flat/recommended'],
    prettierConfig,
    {
        files: ['src/**/*.{ts,vue}'],
        languageOptions: {
            globals: globals.browser,
            parserOptions: {
                parser: tseslint.parser,
            },
        },
        rules: {
            // Vue
            'vue/multi-word-component-names': 'off', // allow single-word view names
            'vue/no-unused-vars': 'error',

            // TypeScript
            '@typescript-eslint/no-unused-vars': ['error', {argsIgnorePattern: '^_'}],
            '@typescript-eslint/no-explicit-any': 'warn',

            // General
            'no-console': ['warn', {allow: ['warn', 'error']}],
        },
    },
    {
        ignores: ['dist/', 'node_modules/', 'coverage/'],
    },
)
