// Avoid Mako's skipModules resolver panic on Umi's generated @umijs/max alias.
export default { mako: { optimization: { skipModules: false } } };
