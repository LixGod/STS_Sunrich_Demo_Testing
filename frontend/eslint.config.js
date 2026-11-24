// @ts-check
const fs = require('node:fs');
const path = require('node:path');
const eslint = require('@eslint/js');
const { defineConfig } = require('eslint/config');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

// Module independence: a feature may not import another feature; core and shared may not import any feature.
const featuresDir = path.join(__dirname, 'src/app/features');
const features = fs.readdirSync(featuresDir).filter((name) => fs.statSync(path.join(featuresDir, name)).isDirectory());

const featureBoundaries = features.map((feature) => ({
  files: [`src/app/features/${feature}/**/*.ts`],
  rules: {
    'no-restricted-imports': [
      'error',
      {
        patterns: features
          .filter((other) => other !== feature)
          .map((other) => ({
            group: [`**/features/${other}/**`, `../${other}/**`, `../../${other}/**`],
            message: `Feature "${feature}" must not import feature "${other}". Move shared code to core/ or shared/.`,
          })),
      },
    ],
  },
}));

module.exports = defineConfig([
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': ['error', { type: 'attribute', prefix: 's2s', style: 'camelCase' }],
      '@angular-eslint/component-selector': ['error', { type: 'element', prefix: 's2s', style: 'kebab-case' }],
      '@angular-eslint/prefer-on-push-component-change-detection': 'error',
    },
  },
  ...featureBoundaries,
  {
    files: ['src/app/core/**/*.ts', 'src/app/shared/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        { patterns: [{ group: ['**/features/**'], message: 'core/ and shared/ must not depend on features.' }] },
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {},
  },
]);
