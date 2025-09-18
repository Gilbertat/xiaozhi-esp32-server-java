// The Vue build version to load with the `import` command
// (runtime-only or standalone) has been set in webpack.base.conf with an alias.
import Vue from "vue";
import App from "./App";
import router from "./router";
import store from "./store";
import axios from "axios";
import Moment from "moment";
import Antd from "ant-design-vue";
// import 'ant-design-vue/dist/antd.css'
import NProgress from "nprogress";
// import 'nprogress/nprogress.css'
// import VueCropper from 'vue-cropper'
// import VCharts from 'v-charts'
import "static/css/main.css";
import i18n from './i18n';

Moment.locale("zh_CN");

Vue.use(NProgress);
router.beforeEach((to, from, next) => {
  NProgress.start();
  next();
});
NProgress.configure({ easing: "ease", speed: 500, showSpinner: false });
router.afterEach(() => {
  NProgress.done();
});
Vue.use(Antd);
Vue.use(window["vue-cropper"]);
Vue.prototype.moment = Moment;

new Vue({
  router,
  store,
  i18n,
  render: h => h(App)
}).$mount("#app");
