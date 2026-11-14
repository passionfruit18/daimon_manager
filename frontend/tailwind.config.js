/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        obsidian: {
          DEFAULT: '#0b0f19',
          900: '#070a12',
          800: '#0b0f19',
          700: '#111827',
          600: '#1a2236',
          500: '#243049',
        },
        neon: {
          green: '#10b981',
          magenta: '#ec4899',
          cyan: '#06b6d4',
          yellow: '#eab308',
          violet: '#8b5cf6',
        },
      },
      fontFamily: {
        mono: ['"JetBrains Mono"', '"SF Mono"', 'ui-monospace', 'Menlo', 'Consolas', 'monospace'],
        display: ['"Space Grotesk"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
      boxShadow: {
        'glow-cyan': '0 0 15px rgba(6,182,212,0.15)',
        'glow-cyan-lg': '0 0 30px rgba(6,182,212,0.35)',
        'glow-magenta': '0 0 15px rgba(236,72,153,0.2)',
        'glow-green': '0 0 15px rgba(16,185,129,0.2)',
        'glow-yellow': '0 0 15px rgba(234,179,8,0.2)',
      },
      keyframes: {
        flicker: {
          '0%, 100%': { opacity: '1' },
          '41%': { opacity: '1' },
          '42%': { opacity: '0.6' },
          '43%': { opacity: '1' },
          '77%': { opacity: '0.85' },
        },
        glitch: {
          '0%, 100%': { transform: 'translate(0,0)' },
          '20%': { transform: 'translate(-1.5px, 1px)' },
          '40%': { transform: 'translate(1.5px, -1px)' },
          '60%': { transform: 'translate(-1px, 0)' },
          '80%': { transform: 'translate(1px, 1px)' },
        },
        float: {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-4px)' },
        },
        scan: {
          '0%': { transform: 'translateY(-100%)' },
          '100%': { transform: 'translateY(100%)' },
        },
      },
      animation: {
        flicker: 'flicker 4s linear infinite',
        glitch: 'glitch 0.6s steps(2) infinite',
        float: 'float 4s ease-in-out infinite',
        scan: 'scan 6s linear infinite',
      },
    },
  },
  plugins: [],
};
