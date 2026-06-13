/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './components/**/*.{vue,js,ts}',
    './layouts/**/*.vue',
    './pages/**/*.vue',
    './plugins/**/*.{js,ts}',
    './app.vue',
    './error.vue'
  ],
  theme: {
    extend: {
      colors: {
        primary: {
          DEFAULT: '#2f6f5e',
          hover: '#245947',
          soft: 'rgba(47, 111, 94, 0.08)'
        },
        accent: '#c97b3f',
        bg: '#fafaf7',
        card: '#ffffff',
        line: '#e8e4d8',
        muted: '#8b8475'
      },
      fontFamily: {
        sans: ['Outfit', 'Noto Serif SC', '-apple-system', 'sans-serif'],
        serif: ['DM Serif Display', 'Noto Serif SC', 'serif'],
        mono: ['JetBrains Mono', 'monospace']
      }
    }
  },
  darkMode: 'class',
  plugins: []
}
