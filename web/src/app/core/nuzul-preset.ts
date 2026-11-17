import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

/** "Riad calm" (docs/ui-nuzul.md): terracotta primary on Aura. Full token set lands in Sprint 4.1. */
export const NuzulPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '#fbf1ec',
      100: '#f5dccf',
      200: '#ebb9a0',
      300: '#df9270',
      400: '#cf6f48',
      500: '#b5532e',
      600: '#984425',
      700: '#7a361e',
      800: '#5d2917',
      900: '#401c10',
      950: '#2a1209',
    },
  },
});
