import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

/** PrimeNG actions use the burgundy in the supplied Ship2Ship logo. */
export const Ship2ShipTheme = definePreset(Aura, {
  semantic: {
    primary: {
      50: '#fbf1f2',
      100: '#f6dfe1',
      200: '#eabdc1',
      300: '#d88c93',
      400: '#bc5962',
      500: '#9b2c36',
      600: '#84252e',
      700: '#6e2028',
      800: '#5b1e25',
      900: '#4d1d23',
      950: '#2c0d11',
    },
    colorScheme: {
      light: {
        primary: {
          color: '{primary.500}',
          contrastColor: '#ffffff',
          hoverColor: '{primary.600}',
          activeColor: '{primary.700}',
        },
        highlight: {
          background: '{primary.50}',
          focusBackground: '{primary.100}',
          color: '{primary.700}',
          focusColor: '{primary.800}',
        },
        surface: {
          0: '#ffffff',
          50: '#f6f3f1',
          100: '#eee8e5',
          200: '#e5ddda',
          300: '#cdc3bf',
          400: '#9a948f',
          500: '#6f6a66',
          600: '#524e4b',
          700: '#3d3a38',
          800: '#2b2928',
          900: '#1c1b1a',
          950: '#121110',
        },
      },
    },
  },
});
