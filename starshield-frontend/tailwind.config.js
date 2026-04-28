/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js,ts}'],
  theme: {
    extend: {
      fontFamily: {
        display: ['Manrope', 'Inter', 'system-ui', 'sans-serif'],
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      colors: {
        stitch: {
          primary: '#4e4af2',
          surface: '#f2f4fb',
        },
      },
    },
  },
  corePlugins: {
    /* 保留 Element Plus 默认样式，仅用 Tailwind 做布局层 */
    preflight: false,
  },
  plugins: [],
}
