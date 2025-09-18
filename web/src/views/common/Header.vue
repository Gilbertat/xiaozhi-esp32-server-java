<template>
  <div id="header">
    <div style="flex: 1 1 0%"></div>
    <div class="header-index-right">
      <a-dropdown class="header-index-account">
        <div>
          <a-avatar
            :src="getAvatarUrl(user.avatar)"
            size="small"
            style="margin-right: 8px"
            icon="user"
          />
          <span>{{ user.name }}</span>
        </div>
        <a-menu slot="overlay">
          <a-menu-item>
            <span class="header-index-dropdown">
              <a-icon type="user" />
              <a @click="$router.push('/setting/account')">{{ $t('personalCenter') }}</a>
            </span>
          </a-menu-item>
          <a-menu-item>
            <span class="header-index-dropdown">
              <a-icon type="setting" />
              <a @click="$router.push('/setting/config')">{{ $t('personalSettings') }}</a>
            </span>
          </a-menu-item>
          <a-menu-divider />
          <a-menu-item>
            <span class="header-index-logout header-index-dropdown">
              <a-icon type="logout" />
              <a href="javascript:;" @click="logout">{{ $t('logout') }}</a>
            </span>
          </a-menu-item>
        </a-menu>
      </a-dropdown>
    </div>
  </div>
</template>

<script>
import Cookies from 'js-cookie'
import { getResourceUrl } from '@/services/axios'

export default {
  data () {
    return {
      user: {}
    }
  },
  computed: {
    userInfo () {
      return this.$store.getters.USER_INFO
    }
  },
  created () {
    this.user = this.userInfo
  },
  methods: {
    logout () {
      Cookies.remove('userInfo')
      this.$router.push('/login')
    },
    getAvatarUrl(avatar) {
      return getResourceUrl(avatar);
    }
  }
}
</script>

<style scoped lang="scss">
#header {
  background: #fff;
  box-shadow: 0 2px 8px #f0f1f2;
  position: relative;
  display: flex;
  z-index: 10;
  max-width: 100%;
  line-height: 48px;
  padding: 0 40px;
}
#header > * {
  height: 100%;
}

.header-index-right {
  cursor: pointer;
  display: flex;
  float: right;
  height: 48px;
  margin-left: auto;
  overflow: hidden;
  .header-index-account {
    padding: 0 12px;
  }
}
.header-index-dropdown a {
  color: rgba(0, 0, 0, 0.45);
}
</style>