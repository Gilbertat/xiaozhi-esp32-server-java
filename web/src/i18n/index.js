import Vue from 'vue'
import VueI18n from 'vue-i18n'
import en from './en'
import zh from './zh'
import ko from './ko'

Vue.use(VueI18n)

// Get browser language
function getBrowserLanguage() {
  const languages = navigator.languages || [navigator.language || navigator.userLanguage]
  for (let lang of languages) {
    // Extract primary language code
    const primaryLang = lang.split('-')[0].toLowerCase()
    if (primaryLang === 'zh') return 'zh'
    if (primaryLang === 'en') return 'en'
    if (primaryLang === 'ko') return 'ko'
  }
  return 'en' // default to English
}

const messages = {
  en,
  zh,
  ko
}

const i18n = new VueI18n({
  locale: getBrowserLanguage(), // set locale
  fallbackLocale: 'en', // set fallback locale
  messages // set locale messages
})

export default i18n