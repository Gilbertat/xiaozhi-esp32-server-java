module.exports = {
  content: [
    "./src/**/*.{js,jsx,ts,tsx}",
    "./public/index.html"
  ],
  theme: {
    extend: {
      colors: {
        // 主色调 - 温暖科技感蓝青色系
        'mira-primary': {
          50: '#f0f9ff',
          100: '#e0f2fe',
          200: '#bae6fd',
          300: '#7dd3fc',
          400: '#38bdf8',
          500: '#0ea5e9', // 主要蓝色
          600: '#0284c7',
          700: '#0369a1',
          800: '#075985',
          900: '#0c4a6e',
        },
        'mira-accent': {
          50: '#f0fdfa',
          100: '#ccfbf1',
          200: '#99f6e4',
          300: '#5eead4',
          400: '#2dd4bf', // 青绿色
          500: '#14b8a6',
          600: '#0d9488',
          700: '#0f766e',
          800: '#115e59',
          900: '#134e4a',
        },
        'mira-gradient': {
          // 渐变色 - 温暖科技感
          'blue-pink': 'linear-gradient(135deg, #0ea5e9 0%, #ec4899 100%)', // 蓝色到粉色
          'blue-green': 'linear-gradient(135deg, #0ea5e9 0%, #10b981 100%)', // 蓝色到青绿
          'teal-purple': 'linear-gradient(135deg, #14b8a6 0%, #8b5cf6 100%)', // 青绿到紫色
        },
        // 背景色 - 柔和温暖
        'mira-bg': {
          light: '#f8fafc',      // 极浅灰白
          card: '#ffffff',       // 卡片背景
          cardHover: '#f1f5f9',  // 卡片悬停
          overlay: 'rgba(248, 250, 252, 0.8)', // 半透明覆盖
          glass: 'rgba(255, 255, 255, 0.25)',  // 玻璃拟态
        },
        // 状态色
        'online': '#10b981',     // 绿色 - 在线
        'offline': '#ef4444',    // 红色 - 离线
        'connecting': '#f59e0b', // 黄色 - 连接中
      },
      boxShadow: {
        'mira-glow': '0 0 20px rgba(14, 165, 233, 0.2)',
        'mira-glow-lg': '0 0 30px rgba(14, 165, 233, 0.3)',
        'mira-card': '0 4px 6px rgba(0, 0, 0, 0.05), 0 10px 15px rgba(0, 0, 0, 0.1)',
        'mira-card-hover': '0 10px 25px rgba(0, 0, 0, 0.1), 0 0 20px rgba(14, 165, 233, 0.2)',
      },
      animation: {
        'float': 'float 6s ease-in-out infinite',
        'pulse-slow': 'pulse 4s cubic-bezier(0.4, 0, 0.6, 1) infinite',
        'glow': 'glow 2s ease-in-out infinite alternate',
      },
      keyframes: {
        float: {
          '0%, 100%': { transform: 'translateY(0px)' },
          '50%': { transform: 'translateY(-10px)' },
        },
        glow: {
          '0%': { boxShadow: '0 0 10px rgba(14, 165, 233, 0.3)' },
          '100%': { boxShadow: '0 0 20px rgba(14, 165, 233, 0.6)' },
        }
      },
      backdropBlur: {
        xs: '2px',
      }
    },
  },
  plugins: [],
}